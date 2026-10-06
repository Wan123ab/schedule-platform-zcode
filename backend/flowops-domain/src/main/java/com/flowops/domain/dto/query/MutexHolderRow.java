package com.flowops.domain.dto.query;

import lombok.Data;

/** 恢复期投影：互斥锁持有点（重建 Redis 锁，docs/06 §10.2 ③）。 */
@Data
public class MutexHolderRow {

    /** task_step.id */
    private Long id;

    private String stepInstanceId;

    private String mutexGroup;

    private String dispatchToken;

    private Long enqueueSeq;

    private Long queueId;

    private Integer priority;

    /** 持有者描述（task.businessId + 步骤实例编号，与 tryAcquire 写入格式一致） */
    private String taskId;
}
