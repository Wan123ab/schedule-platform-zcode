package com.flowops.modules.dashboard.dto;

import lombok.Data;

/** 队列积压（CONTRACT §11；waitingCount 取冗余列 waiting_task_count）。 */
@Data
public class QueueBacklogVO {

    private String queueId;

    private String queueName;

    private Integer waitingCount;

    private Integer maxWaiting;
}
