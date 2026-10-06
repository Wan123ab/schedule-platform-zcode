package com.flowops.scheduler.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.flowops.domain.mapper.task.TaskMapper;

/**
 * 启动恢复骨架（docs/03 §5.1 RecoveryService，D-09）：
 * 调度器启动/接管主后，从 PG 重建 Redis 派生态 —— 权威状态在 PG，Redis 只放可重建派生态。
 * M1 按 docs/06 §10.2 落地：
 *   ① 扫描 status ∈ (PENDING, SCHEDULING, RUNNING, STOPPING) 的任务
 *   ② 重建就绪队列 ZSet（含 enqueue_seq 回填）
 *   ③ 重建互斥锁持有点（task_step.mutex_holder = true AND status = 'RUNNING'）
 *   ④ 重建节点预留账本（Σ 非终态步骤资源申请量，D-22）
 *   ④b 重建互斥锁等待者 ZSet（docs/06 §6.3.1）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryService implements ApplicationRunner {

    private final TaskMapper taskMapper;

    @Override
    public void run(ApplicationArguments args) {
        // 只做活跃任务统计作为启动自检；队列/互斥/预留账本重建在 M1 实现（成为 leader 后执行，接管场景同样触发）
        long active = taskMapper.countActiveTasks();
        log.info("恢复自检完成：当前活跃任务数={}（派生态重建在 M1 实现，docs/06 §10.2）", active);
    }
}
