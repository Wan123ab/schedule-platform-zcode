package com.flowops.domain.dto.query;

import lombok.Data;

/** 步骤实例运行时行（任务编排器的内存状态初始化输入）。 */
@Data
public class StepRuntimeRow {

    /** task_step.id —— 出队/CAS 的操作对象 */
    private Long id;
    private String stepInstanceId;
    /** workflow_step.id（与图节点对应） */
    private Long stepId;
    private String status;
    private String mutexGroup;
    private Long enqueueSeq;
    /** 已重试次数（attempt_no = retry_count + 1，D-23 三元组） */
    private Integer retryCount;
    /** 资源申请 JSON（{cpu,gpu,memory,disk}；解析失败按 0 计并告警，docs/06 §10.2 ④ 防御口径） */
    private String resourceRequest;

    /** 起始命令（M1 直传执行器；变量 6 层解析随 M3 落地后由 VariableResolveManager 产出） */
    private String startCommand;

    /** 重试上限（继承链 M4 完整解析；M1 取 COALESCE(实例行, 步骤定义)） */
    private Integer maxRetryCount;

    /** 重试间隔秒（docs/06 §9.1：next_retry_at = now + interval） */
    private Integer retryIntervalSeconds;
}
