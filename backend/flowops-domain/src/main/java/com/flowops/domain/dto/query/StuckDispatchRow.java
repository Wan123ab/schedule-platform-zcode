package com.flowops.domain.dto.query;

import lombok.Data;

/** SCHEDULING 卡住步骤行（回执丢失扫描，docs/06 §4.6 / §8.3）。 */
@Data
public class StuckDispatchRow {

    /** task_step.id */
    private Long id;

    private String stepInstanceId;

    private String dispatchToken;

    private Long enqueueSeq;

    private Long queueId;

    private Integer priority;
}
