package com.flowops.domain.dto.query;

import lombok.Data;

import java.time.OffsetDateTime;

/** 到期重试步骤行（RetryHandler 阶段⑨的扫描输入，docs/06 §9.1）。 */
@Data
public class RetryingStepRow {

    /** task_step.id */
    private Long id;

    private String stepInstanceId;

    private String status;

    private Long enqueueSeq;

    /** 所属任务冗余（重排队需要 queue_id 与 priority） */
    private Long taskId;

    private Long queueId;

    private Integer priority;
}
