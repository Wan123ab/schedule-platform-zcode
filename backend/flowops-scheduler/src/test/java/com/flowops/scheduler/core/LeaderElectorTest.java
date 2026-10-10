package com.flowops.scheduler.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 选主器单测（docs/06 §10.1 + docs/07 /platform/health leader_id）。
 * 关键行为：成为主后把 instanceId 写入 INSTANCE_KEY（TTL 60s，10s 心跳续期）——
 * server 端 DashboardService 经该 key 读 leader_id；锁 value 是 Redisson 内部持有者标识不可用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderElectorTest {

    private static final String INSTANCE_ID = "sched-test01";

    @Mock private RedissonClient redisson;
    @Mock private RLock lock;
    @Mock private RBucket<String> instanceBucket;

    private LeaderElector elector;

    @BeforeEach
    void setUp() {
        elector = new LeaderElector(redisson);
        ReflectionTestUtils.setField(elector, "instanceId", INSTANCE_ID);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.<String>getBucket(LeaderElector.INSTANCE_KEY)).thenReturn(instanceBucket);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        elector.destroy();
    }

    @Test
    void 抢锁成功_发布实例标识并成为主() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);

        elector.start();

        assertThat(elector.isLeader()).isTrue();
        assertThat(elector.getInstanceId()).isEqualTo(INSTANCE_ID);
        verify(instanceBucket).set(INSTANCE_ID, 60, TimeUnit.SECONDS);
    }

    @Test
    void 主节点心跳_续期实例标识() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        elector.start();

        // 模拟 10s 心跳轮（leader 分支：不再抢锁，仅续期 instance key）
        java.lang.reflect.Method tryAcquire = LeaderElector.class.getDeclaredMethod("tryAcquire");
        tryAcquire.setAccessible(true);
        tryAcquire.invoke(elector);

        verify(lock, org.mockito.Mockito.times(1)).tryLock(0, TimeUnit.SECONDS);
        verify(instanceBucket, org.mockito.Mockito.times(2)).set(INSTANCE_ID, 60, TimeUnit.SECONDS);
    }

    @Test
    void 抢锁失败_不发布标识也不成为主() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        elector.start();

        assertThat(elector.isLeader()).isFalse();
        verify(instanceBucket, never()).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    @Test
    void 主节点释放_清理实例标识() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        elector.start();
        assertThat(elector.isLeader()).isTrue();

        elector.destroy();

        verify(lock).unlock();
        verify(instanceBucket).delete();
        assertThat(elector.isLeader()).isTrue(); // leader 标志只在重新选主时翻转，destroy 不改
    }

    @Test
    void 未持锁释放_不清理实例标识() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
        elector.start();

        elector.destroy();

        verify(lock, never()).unlock();
        verify(instanceBucket, never()).delete();
    }
}
