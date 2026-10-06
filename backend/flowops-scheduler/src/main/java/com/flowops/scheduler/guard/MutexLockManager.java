package com.flowops.scheduler.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;

/**
 * 互斥锁管理器（G7，docs/06 §6.3 + §6.3.1）。
 *
 * <p><b>锁语义</b>：同名 mutexGroup 全平台串行；步骤进入终态必须释放（释放幂等）。</p>
 *
 * <p><b>三个实现要点（各有一次事故推演背书）：</b></p>
 * <ul>
 *   <li><b>跨线程释放</b>：锁在下发线程获取、在回执/生命周期线程释放，而 Redisson RLock 的
 *       持有语义绑定线程 —— 释放走 {@code forceUnlock()}（跨线程安全）；重入防护由业务保证
 *       （同一 group 同一时刻至多一个步骤持有，CAS 出队已保证）。</li>
 *   <li><b>holder 记录带 TTL</b>（§6.3 超时保护）：TTL = max(步骤超时, 1h) × 1.5，由调用方传入。
 *       独立的超时强释扫描在 M1 生命周期扫描器落地；TTL 到期自动消失是最后防线。</li>
 *   <li><b>等待者 ZSet</b>（§6.3.1）：拿锁失败的步骤进入 waiters（score = 原 enqueue_seq，
 *       FIFO 公平性）；释放时弹出一个等待者交还调用方——member 编码为
 *       {@code stepId:seq:priority:queueId}，让弹出与"原序号 + 原优先级 + 原队列"原子同包，
 *       调用方可直接把等待者重排回就绪队列（保持原 score，防饥饿）。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class MutexLockManager {

    public static final String LOCK_PREFIX = "flowops:mutex:";
    private static final String HOLDER_SUFFIX = ":holder";
    private static final String WAITERS_SUFFIX = ":waiters";
    private static final String MEMBER_SEPARATOR = ":";

    private final RedissonClient redisson;

    /** 弹出的等待者：调用方将其按原 (seq, priority, queue) 重排回就绪队列，下一 tick 走标准派发路径。 */
    public record Waiter(long taskStepId, long enqueueSeq, int priority, long queueId, String mutexGroup) {}

    /**
     * 尝试获取互斥锁。
     *
     * @param group         互斥组名（全平台串行）
     * @param taskStepId    竞争的步骤实例 id
     * @param enqueueSeq    该步骤原入队序号（拿锁失败时进 waiters，保 FIFO）
     * @param priority      该步骤任务的优先级（唤醒重排队时保序）
     * @param holderDesc    持有者描述，如 "task:TASK-xxx/step:数据同步"（诊断面板展示用）
     * @param holderTtl     holder 记录 TTL（超时保护，§6.3 ①）
     * @return true = 拿到锁；false = 未拿到（已登记为等待者）
     */
    public boolean tryAcquire(String group, long taskStepId, long enqueueSeq, int priority, long queueId,
                              String holderDesc, Duration holderTtl) {
        RLock lock = redisson.getLock(LOCK_PREFIX + group);
        // tryLock() 无参：拿不到立即失败（调度语义不允许等待阻塞主循环）；持有期靠看门狗续期
        if (!lock.tryLock()) {
            registerWaiter(group, taskStepId, enqueueSeq, priority, queueId);
            return false;
        }
        RBucket<String> holder = redisson.getBucket(LOCK_PREFIX + group + HOLDER_SUFFIX);
        holder.set(holderDesc, holderTtl);
        log.debug("互斥锁获取 group={} holder={}", group, holderDesc);
        return true;
    }

    /**
     * 释放锁并弹出下一个等待者。
     *
     * @return 下一个等待者（可能为空）——调用方将其按原 (seq, priority) 重排回就绪队列，
     *         下一 tick 走标准派发路径重新竞争节点与锁（docs/06 §6.3.1 的"同 tick 唤醒"
     *         需 Lua 原子交接，一期以"重排队 + 1 tick 延迟"等价实现，见 pipeline 注释）
     */
    public Optional<Waiter> release(String group, long taskStepId) {
        RLock lock = redisson.getLock(LOCK_PREFIX + group);
        lock.forceUnlock();                       // 跨线程释放（见类注释）；幂等
        redisson.getBucket(LOCK_PREFIX + group + HOLDER_SUFFIX).delete();

        RScoredSortedSet<String> waiters = waitersOf(group);
        String member = waiters.pollFirst();      // 弹出 seq 最小的等待者（ZPOPMIN 语义）
        if (member == null) {
            return Optional.empty();
        }
        Waiter waiter = decode(group, member);
        log.debug("互斥锁释放并唤醒 group={} waiter={} seq={}", group, waiter.taskStepId(), waiter.enqueueSeq());
        return Optional.of(waiter);
    }

    /** 当前持有者描述（诊断接口 / blocking_reasons 用，docs/07 §6.6）。 */
    public Optional<String> holderOf(String group) {
        return Optional.ofNullable(redisson.<String>getBucket(LOCK_PREFIX + group + HOLDER_SUFFIX).get());
    }

    /** 等待者数量（指标 mutex.waiters{group}，docs/06 §14.1）。 */
    public int waitersCount(String group) {
        return waitersOf(group).size();
    }

    private void registerWaiter(String group, long taskStepId, long enqueueSeq, int priority, long queueId) {
        waitersOf(group).add(enqueueSeq, encode(taskStepId, enqueueSeq, priority, queueId));
    }

    private RScoredSortedSet<String> waitersOf(String group) {
        return redisson.getScoredSortedSet(LOCK_PREFIX + group + WAITERS_SUFFIX);
    }

    private static String encode(long taskStepId, long enqueueSeq, int priority, long queueId) {
        return taskStepId + MEMBER_SEPARATOR + enqueueSeq + MEMBER_SEPARATOR + priority
                + MEMBER_SEPARATOR + queueId;
    }

    private static Waiter decode(String group, String member) {
        String[] parts = member.split(MEMBER_SEPARATOR);
        return new Waiter(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                Integer.parseInt(parts[2]), Long.parseLong(parts[3]), group);
    }
}
