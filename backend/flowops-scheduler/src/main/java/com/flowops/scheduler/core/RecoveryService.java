package com.flowops.scheduler.core;

import com.flowops.domain.dto.query.LedgerRecoveryRow;
import com.flowops.domain.dto.query.MutexHolderRow;
import com.flowops.domain.dto.query.WaitingRecoveryRow;
import com.flowops.domain.mapper.task.RecoveryQueryMapper;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.match.ResourceRequests;
import com.flowops.scheduler.queue.ReadyQueueManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 启动恢复（D-09，docs/06 §10.2）—— "PG 是唯一真源，Redis 只放可重建派生态"的价值兑现点。
 *
 * <p><b>时序（§10.3）</b>：恢复必须发生在成为 leader 之后、主循环派发之前 ——
 * 本类启动一个守护线程等待领导权，首次成为 leader 时执行一次 {@link #recover()}，
 * 此后主循环（SchedulerEngine.tick 的 leader 判断）自然接管。崩溃重启与主备切换走同一条路径。</p>
 *
 * <p><b>幂等性（§10.3）</b>：ZSet 重建用 ZADD（重复执行结果一致）；锁用 tryAcquire（幂等）；
 * 账本用 rebuild（整体覆盖）。恢复中途崩溃 → 重启后重来一遍，结果一致。</p>
 */
@Slf4j
@Component
public class RecoveryService implements ApplicationRunner {

    /** 单批恢复上限（活跃任务 × 步骤可控，docs/06 §7.1：500 任务 × 10 步量级）。 */
    private static final int BATCH_LIMIT = 5000;

    private final RecoveryQueryMapper recoveryQuery;
    private final ReadyQueueManager readyQueue;
    private final ReservedLedger ledger;
    private final MutexLockManager mutexes;
    private final LeaderElector leaderElector;
    private final Duration holderTtl;

    public RecoveryService(RecoveryQueryMapper recoveryQuery, ReadyQueueManager readyQueue,
                           ReservedLedger ledger, MutexLockManager mutexes, LeaderElector leaderElector,
                           @Value("${flowops.scheduler.mutex-holder-ttl-minutes:90}") long holderTtlMinutes) {
        this.recoveryQuery = recoveryQuery;
        this.readyQueue = readyQueue;
        this.ledger = ledger;
        this.mutexes = mutexes;
        this.leaderElector = leaderElector;
        this.holderTtl = Duration.ofMinutes(holderTtlMinutes);
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::awaitLeadershipAndRecover, "flowops-recovery");
        worker.setDaemon(true);
        worker.start();
    }

    /** 每 3s 检查一次领导权（§10.1 standby 抢锁节奏对齐）；首次成为 leader 即恢复。 */
    private void awaitLeadershipAndRecover() {
        while (!leaderElector.isLeader()) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            recover();
        } catch (Exception e) {
            // 恢复失败不能静默：派生态缺失会走向卡死/超分，必须醒目告警（docs/06 §10.3）
            log.error("启动恢复失败（Redis 派生态可能不完整，请按 Runbook 处置）", e);
        }
    }

    void recover() {
        long startedAt = System.currentTimeMillis();

        // ② 重建就绪队列 ZSet：WAITING_RESOURCE 全量（score 用原 priority + enqueue_seq，
        //    FIFO 顺序与重启前一致；互斥等待者不单独重建 —— 重新派发时按 mutex_group 再竞争，
        //    waiters ZSet 由拿锁失败路径自然重建，语义等价且少一个恢复面）
        int queued = 0;
        for (WaitingRecoveryRow row : recoveryQuery.findWaitingSteps(BATCH_LIMIT)) {
            readyQueue.enqueue(row.getQueueId(), row.getId(),
                    row.getPriority() == null ? 0 : row.getPriority(),
                    row.getEnqueueSeq() == null ? 0L : row.getEnqueueSeq());
            queued++;
        }

        // ③ 重建互斥锁持有点：锁因 TTL 过期消失时重新获取；获取失败（单活下不应发生）记录异常
        int holders = 0;
        for (MutexHolderRow row : recoveryQuery.findMutexHolders(BATCH_LIMIT)) {
            String holderDesc = "task:" + row.getTaskId() + "/step:" + row.getStepInstanceId();
            if (!mutexes.tryAcquire(row.getMutexGroup(), row.getId(),
                    row.getEnqueueSeq() == null ? 0L : row.getEnqueueSeq(),
                    row.getPriority() == null ? 0 : row.getPriority(),
                    row.getQueueId() == null ? 0L : row.getQueueId(),
                    holderDesc, holderTtl)) {
                log.error("互斥锁重建失败 group={} step={}（单活下不应发生，请排查）",
                        row.getMutexGroup(), row.getStepInstanceId());
            }
            holders++;
        }

        // ④ 重建预留账本（D-22）：整体覆盖式 rebuild —— 崩溃前的脏账一次清掉
        java.util.List<ReservedLedger.NodeReservation> reservations = new java.util.ArrayList<>();
        for (LedgerRecoveryRow row : recoveryQuery.findLedgerRows(BATCH_LIMIT)) {
            reservations.add(new ReservedLedger.NodeReservation(row.getNodeId(), row.getId(),
                    ResourceRequests.parse(row.getResourceRequest())));
        }
        ledger.rebuild(reservations);

        // ⑤ 僵尸步骤（SCHEDULING 卡住）不在此处理 —— LifecycleScanner.scanStuckDispatch
        //    每 tick 扫描（阈值 2min），恢复后首个 tick 即接管同一逻辑，避免两处判定漂移

        long costMs = System.currentTimeMillis() - startedAt;
        // ⑧ 恢复耗时是"这套架构是否健康"的核心信号（docs/06 §10.2 ⑧）：越来越慢 = 活跃数据膨胀或索引失效
        log.info("启动恢复完成：就绪队列 {} 步 / 互斥持有点 {} / 账本 {} 条，耗时 {}ms",
                queued, holders, reservations.size(), costMs);
    }
}
