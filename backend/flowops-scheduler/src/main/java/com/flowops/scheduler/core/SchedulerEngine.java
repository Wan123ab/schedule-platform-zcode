package com.flowops.scheduler.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调度主循环（docs/03 §5.1 / 06 §3）。
 * 主循环单线程（fixedDelay=1s 保证 tick 串行）—— 调度决策需要队列/资源/并发的一致视图，
 * 并行化 tick 会产生节点超分等竞态（docs/06 §3.1 的事故推演），这是硬约束。
 *
 * <p><b>挂载进度（M1）</b>：tick 已接入 {@link SchedulerTickPipeline}
 * （准入 → DAG 推进入队 → 出队 → 节点匹配 → 互斥 → 下发槽位 → 任务终结）；
 * 心跳/超时/重试扫描器（§3.2 阶段 ⑧⑨）与 SSH 执行链为 M1 后续纵切。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerEngine {

    private final LeaderElector leaderElector;
    private final SchedulerTickPipeline pipeline;

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!leaderElector.isLeader()) {
            return;   // standby 空转：只等抢锁，不做任何决策（docs/06 §10.4）
        }
        long startedAt = System.currentTimeMillis();
        try {
            pipeline.tick();
        } catch (Exception e) {
            // tick 异常不允许中断主循环（下一 tick 的幂等性保证无副作用累积，docs/06 §3.3）
            log.error("tick 异常（下 tick 幂等重试）", e);
        } finally {
            long costMs = System.currentTimeMillis() - startedAt;
            if (costMs > 800) {
                // 指标 scheduler.tick.duration 的日志替身（Micrometer 接入后改为 Timer，docs/06 §14.1）
                log.warn("tick 耗时 {}ms，接近瓶颈阈值（>800ms）", costMs);
            }
        }
    }
}
