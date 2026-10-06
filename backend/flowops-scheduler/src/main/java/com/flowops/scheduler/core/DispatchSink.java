package com.flowops.scheduler.core;

/**
 * 下发槽位（docs/06 §3.2 阶段 ⑦ 的抽象）。
 *
 * <p><b>为什么是接口</b>：调度决策（本 tick 管线）与执行通信（SSH/Agent，flowops-executor-client）
 * 是两个变化方向 —— 管线只负责"选定了谁、带什么幂等键"，怎么连上去是执行链的事。</p>
 *
 * <p><b>实现约定</b>：dispatch() 必须异步返回（不得阻塞 tick）；结果经 CompletionBus 回流，
 * 由主循环线程在下一 tick 收敛 —— 实现方不得在 dispatch 调用线程里改任何调度状态。</p>
 */
public interface DispatchSink {

    /**
     * 提交下发（异步）。实现方自带背压（docs/06 §13.2：有界队列 + 拒绝回退）。
     */
    void dispatch(DispatchInstruction instruction);

    /**
     * 下发指令：携带 D-23 幂等三元组 (step_instance_id, attempt_no, dispatch_token)。
     * 执行侧按 dispatch_token 去重 —— 调度器保证同一次下发重复到达时三元组完全相同。
     */
    record DispatchInstruction(
            long taskStepRowId,
            /** 日志落库需要 task 行 id（task_log.task_id 引用物理主键） */
            long taskRowId,
            String stepInstanceId,
            String dispatchToken,
            int attemptNo,
            String stepName,
            /** 回执收敛所需：队列（失败回退/唤醒重排队）与节点（账本扣回） */
            long queueId,
            long nodeId,
            String nodeName,
            String machineIp,
            String mutexGroup,
            long enqueueSeq,
            int priority,
            /** M1 直传 start_command（变量 6 层解析随 M3）；SSH 直跑 */
            String command) {}
}
