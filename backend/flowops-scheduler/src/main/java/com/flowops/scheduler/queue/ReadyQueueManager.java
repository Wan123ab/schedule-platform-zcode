package com.flowops.scheduler.queue;

import com.flowops.domain.mapper.task.TaskStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 就绪队列管理器（docs/06 §4）—— Redis ZSet 的唯一操作入口。
 *
 * <p><b>数据结构</b>：每队列一个 ZSet（queue 隔离优先于优先级，G2-4），member = taskStepId，
 * score 由 {@link QueueScore} 构造（优先级降序 + 同优先级 FIFO）。</p>
 *
 * <p><b>出队两段式（§4.3，本类的心脏）</b>：</p>
 * <pre>
 *   ① DB CAS 占位（status=WAITING_RESOURCE → SCHEDULING + dispatch_token）
 *      失败 → 已被他方处理 → 顺手 ZREM（幂等清理），跳过；
 *   ② ZREM
 *      失败（返回 0，被并发移除）→ 回滚 ① 的占位（按 token，只退自己的）；
 *   ③ 交给下发池（调用方）
 * </pre>
 * <p>顺序不可颠倒：先 CAS 后 ZREM，最坏情况是"占了位还在队列"（下轮 CAS 失败自然跳过）——
 * 安全方向；反过来会出现"从队列消失但状态没改"的丢步骤窗口。</p>
 *
 * <p><b>队头阻塞（§4.3 v3 补充）</b>：队头步骤反复因"无节点/等互斥"回退时，
 * failTicks 计数；≥ 阈值的 member 在出队候选中被跳过（<b>不删除、不降级</b>——删除会让用户的
 * 任务凭空消失），向后扫描 SCAN_LIMIT 条让后面可调度的步骤先走。回退时 score 不变
 * （保留原 enqueue_seq，防饥饿，§4.5）。</p>
 */
@Slf4j
public class ReadyQueueManager {

    public static final String READY_PREFIX = "flowops:queue:ready:";
    private static final String FAIL_TICKS_PREFIX = "flowops:queue:retrycount:";

    /** 单 tick 单队列最多尝试出队数（防下发风暴，docs/06 §4.3 v3 补充）。 */
    private final int tickDispatchBatch;
    /** 队头阻塞时向后扫描的最大条数。 */
    private final int scanLimit;
    /** failTicks 达到该值即视为"阻塞队头"。 */
    private final int headBlockThreshold;

    private final RedissonClient redisson;
    private final TaskStepMapper taskStepMapper;

    public ReadyQueueManager(RedissonClient redisson, TaskStepMapper taskStepMapper,
                             int tickDispatchBatch, int scanLimit, int headBlockThreshold) {
        this.redisson = redisson;
        this.taskStepMapper = taskStepMapper;
        this.tickDispatchBatch = tickDispatchBatch;
        this.scanLimit = scanLimit;
        this.headBlockThreshold = headBlockThreshold;
    }

    /** 出队结果：拿到下发令牌的步骤（调用方据此组装 ExecuteCommand，D-23 三元组）。 */
    public record Claim(long taskStepId, String dispatchToken) {}

    /** 入队（幂等：ZADD 对已存在 member 覆盖 score —— 优先级重估/插队 = 改 score，§4.4）。 */
    public void enqueue(long queueId, long taskStepId, int priority, long enqueueSeq) {
        readyOf(queueId).add(QueueScore.of(priority, enqueueSeq), String.valueOf(taskStepId));
    }

    /** 移除（用户停止 / 任务超时，§4.5）；Redis 故障时抛出由调用方决定重试。 */
    public void remove(long queueId, long taskStepId) {
        readyOf(queueId).remove(String.valueOf(taskStepId));
    }

    /** 就绪队列长度（指标 queue.ready.size{queueId}，docs/06 §14.1）。 */
    public int size(long queueId) {
        return readyOf(queueId).size();
    }

    /**
     * 产出本 tick 的出队候选（<b>不</b>含占位动作）：
     * batch = min(freeSlots, TICK_DISPATCH_BATCH)；跳过 failTicks 达阈值的阻塞队头成员。
     *
     * @param freeSlots 队列剩余并发槽位（max_concurrent − 运行中步骤数，调用方经
     *                  ConcurrencyQueryMapper.countRunningStepsByQueue 计算）
     */
    public List<Long> drainCandidates(long queueId, long freeSlots) {
        int batch = (int) Math.min(freeSlots, tickDispatchBatch);
        if (batch <= 0) {
            return List.of();   // 队列并发满：本队列本 tick 不出队（§4.4，不取出再放回，避免抖动）
        }
        // 多读 scanLimit 条，为"跳过阻塞队头"留出向后扫描空间
        List<String> window = new ArrayList<>(
                readyOf(queueId).valueRange(0, batch + scanLimit - 1));
        List<Long> candidates = new ArrayList<>(batch);
        for (String member : window) {
            long stepId = Long.parseLong(member);
            Long failTicks = failTicksOf(queueId).get(member);
            if (failTicks != null && failTicks >= headBlockThreshold) {
                log.debug("跳过阻塞队头 queue={} step={} failTicks>={}", queueId, stepId, headBlockThreshold);
                continue;
            }
            candidates.add(stepId);
            if (candidates.size() >= batch) {
                break;
            }
        }
        return candidates;
    }

    /**
     * 两段式占位（§4.3 ①②）：成功返回 Claim（含 dispatchToken），失败返回 empty。
     * 幂等：重复 tick 对同一步骤重复 claim，CAS 必然失败一次后返回 empty。
     */
    public Optional<Claim> claim(long queueId, long taskStepId) {
        String member = String.valueOf(taskStepId);
        String token = UUID.randomUUID().toString().replace("-", "");

        // ① DB CAS 占位
        if (taskStepMapper.casClaimForDispatch(taskStepId, token) == 0) {
            readyOf(queueId).remove(member);   // 他方已处理 → 队列顺手清理（幂等）
            return Optional.empty();
        }
        // ② ZREM
        if (!readyOf(queueId).remove(member)) {
            taskStepMapper.rollbackClaim(taskStepId, token);   // 被并发移除 → 退回占位
            log.warn("ZREM 失败已回滚占位 queue={} step={}", queueId, taskStepId);
            return Optional.empty();
        }
        return Optional.of(new Claim(taskStepId, token));
    }

    // ── 队头阻塞计数（§4.3）────────────────────────────────────

    /** 回退记账（节点匹配失败 / 互斥未获取时由调度阶段调用）；score 不动，保 FIFO。 */
    public void recordFailure(long queueId, long taskStepId) {
        failTicksOf(queueId).addAndGet(String.valueOf(taskStepId), 1L);
    }

    /** 真正下发成功时清除计数。 */
    public void clearFailure(long queueId, long taskStepId) {
        failTicksOf(queueId).remove(String.valueOf(taskStepId));
    }

    /** 队头是否处于阻塞状态（指标 queue.head_blocked{queueId} 与诊断用）。 */
    public boolean headBlocked(long queueId, long taskStepId) {
        Long failTicks = failTicksOf(queueId).get(String.valueOf(taskStepId));
        return failTicks != null && failTicks >= headBlockThreshold;
    }

    private RScoredSortedSet<String> readyOf(long queueId) {
        return redisson.getScoredSortedSet(READY_PREFIX + queueId);
    }

    private org.redisson.api.RMap<String, Long> failTicksOf(long queueId) {
        return redisson.getMap(FAIL_TICKS_PREFIX + queueId,
                new org.redisson.codec.CompositeCodec(
                        org.redisson.client.codec.StringCodec.INSTANCE,
                        org.redisson.client.codec.LongCodec.INSTANCE));
    }
}
