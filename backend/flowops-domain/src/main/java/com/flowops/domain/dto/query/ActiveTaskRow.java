package com.flowops.domain.dto.query;

import lombok.Data;

/** 活跃任务行（调度管线每 tick 的扫描输入，docs/06 §3.2 ①②）。 */
@Data
public class ActiveTaskRow {

    private Long id;
    private String taskId;
    /** PENDING / SCHEDULING / RUNNING（STOPPING 由停止收敛路径处理，不进本管线） */
    private String status;
    private Long workflowId;
    private Long projectId;
    private Long workflowVersionId;
    /** 步骤继承任务队列与优先级（M1 口径；步骤级 target_queue 细分随 M4 诊断完善） */
    private Long queueId;
    private Integer priority;
}
