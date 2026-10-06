package com.flowops.domain.dto.query;

import lombok.Data;

/** 恢复期投影：WAITING_RESOURCE 步骤（重建就绪队列 ZSet，docs/06 §10.2 ②）。 */
@Data
public class WaitingRecoveryRow {

    /** task_step.id（ZSet member） */
    private Long id;

    private String stepInstanceId;

    private Long enqueueSeq;

    private Long queueId;

    private Integer priority;
}
