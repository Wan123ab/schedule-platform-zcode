package com.flowops.scheduler.queue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import com.flowops.domain.mapper.task.TaskStepMapper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReadyQueueManager 集成测试（真实 Redis + Mockito 隔离 DB）。
 * 覆盖 docs/06 §16 边界用例 2（同优先级 FIFO 确定）、3（回退保序防饥饿的计数面）
 * 与 §4.3 出队两段式的三个分支。
 */
class ReadyQueueManagerTest {

    private static RedissonClient redisson;
    private static ReadyQueueManager manager;
    private static final AtomicLong QUEUE_SEQ = new AtomicLong(910_000);

    @BeforeAll
    static void setUp() {
        assumeTrue(redisAvailable(), "Redis 不可达，跳过就绪队列集成测试（CI 中必然可达）");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        redisson = Redisson.create(config);
        manager = new ReadyQueueManager(redisson, mock(TaskStepMapper.class), 64, 256, 10);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    private static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 每个用例独立的队列号，避免用例间串扰。 */
    private static long queue() {
        long q = QUEUE_SEQ.incrementAndGet();
        redisson.getScoredSortedSet(ReadyQueueManager.READY_PREFIX + q).clear();
        redisson.getMap("flowops:queue:retrycount:" + q).clear();
        return q;
    }

    @Test
    void 优先级降序_同优先级FIFO_出队顺序确定() {
        long q = queue();
        manager.enqueue(q, 1L, 0, 101);     // 低优
        manager.enqueue(q, 2L, 100, 102);   // 高优
        manager.enqueue(q, 3L, 50, 103);
        manager.enqueue(q, 4L, 50, 100);    // 与 3 同优先级，但 seq 更小 → 先出（M-09：INCR 保严格 FIFO）

        List<Long> order = manager.drainCandidates(q, 10);

        assertThat(order).containsExactly(2L, 4L, 3L, 1L);
    }

    @Test
    void 批量上限_freeSlots与TICK_BATCH取小() {
        long q = queue();
        for (long i = 1; i <= 5; i++) {
            manager.enqueue(q, i, 0, i);
        }
        assertThat(manager.drainCandidates(q, 3)).hasSize(3);     // 槽位约束
    }

    @Test
    void 队列满_本tick不出队() {
        long q = queue();
        manager.enqueue(q, 1L, 0, 1);
        assertThat(manager.drainCandidates(q, 0)).isEmpty();      // §4.4：不看队列直接跳过，不抖动
    }

    @Test
    void 队头阻塞_跳过达阈值的member_让后方步骤先走() {
        long q = queue();
        manager.enqueue(q, 1L, 0, 1);       // 队头：反复回退
        manager.enqueue(q, 2L, 0, 2);       // 后方：可调度

        for (int i = 0; i < 10; i++) {      // 达到 HEAD_BLOCK_THRESHOLD = 10
            manager.recordFailure(q, 1L);
        }
        assertThat(manager.headBlocked(q, 1L)).isTrue();

        List<Long> candidates = manager.drainCandidates(q, 10);

        // 阻塞成员不被删除、不降级（用户任务不能凭空消失），只是让路
        assertThat(candidates).containsExactly(2L);
        assertThat(manager.size(q)).isEqualTo(2);
    }

    @Test
    void claim_两段式成功_返回token且member出队() {
        long q = queue();
        manager.enqueue(q, 7L, 0, 1);
        TaskStepMapper mapper = mock(TaskStepMapper.class);
        ReadyQueueManager m = new ReadyQueueManager(redisson, mapper, 64, 256, 10);
        when(mapper.casClaimForDispatch(org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(1);

        Optional<ReadyQueueManager.Claim> claim = m.claim(q, 7L);

        assertThat(claim).isPresent();
        assertThat(claim.get().taskStepId()).isEqualTo(7L);
        assertThat(claim.get().dispatchToken()).hasSize(32);      // D-23 三元组的 token
        assertThat(m.size(q)).isZero();                           // ② ZREM 完成
        verify(mapper, never()).rollbackClaim(anyLong(), anyString());
    }

    @Test
    void claim_CAS失败_说明已被他方处理_顺手清理队列() {
        long q = queue();
        manager.enqueue(q, 7L, 0, 1);
        TaskStepMapper mapper = mock(TaskStepMapper.class);
        ReadyQueueManager m = new ReadyQueueManager(redisson, mapper, 64, 256, 10);
        when(mapper.casClaimForDispatch(org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(0);

        assertThat(m.claim(q, 7L)).isEmpty();
        assertThat(m.size(q)).isZero();                            // 幂等清理（§4.3 ① 分支）
        verify(mapper, never()).rollbackClaim(anyLong(), anyString());
    }

    @Test
    void claim_ZREM失败_回滚自己的占位() {
        long q = queue();
        TaskStepMapper mapper = mock(TaskStepMapper.class);
        ReadyQueueManager m = new ReadyQueueManager(redisson, mapper, 64, 256, 10);
        // member 不在队列（模拟被并发移除）：CAS 成功但 ZREM 返回 0
        when(mapper.casClaimForDispatch(org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(1);

        assertThat(m.claim(q, 7L)).isEmpty();
        verify(mapper).rollbackClaim(eq(7L), anyString());   // ② 的回滚，只退自己的位
    }

    @Test
    void 下发成功_清除失败计数() {
        long q = queue();
        manager.recordFailure(q, 1L);
        manager.recordFailure(q, 1L);
        assertThat(manager.headBlocked(q, 1L)).isFalse();          // 2 < 10

        manager.clearFailure(q, 1L);
        assertThat(manager.headBlocked(q, 1L)).isFalse();
    }
}
