package com.flowops.domain.dto.query;

import lombok.Data;

/** 队列并发配置读模型（docs/05 §3.3 queue 表的并发列投影）。 */
@Data
public class QueueConcurrencyConfig {

    private Long queueId;

    /** 队列级并发上限（出队时检查，docs/06 §6.1） */
    private Integer maxConcurrentTasks;
}
