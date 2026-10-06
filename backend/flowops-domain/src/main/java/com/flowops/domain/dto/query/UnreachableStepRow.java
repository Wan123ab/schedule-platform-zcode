package com.flowops.domain.dto.query;

import lombok.Data;

/** 失联节点上的运行中步骤（docs/06 §8.1 ⑤ 恢复窗口到期）。 */
@Data
public class UnreachableStepRow {

    private Long id;

    private String stepInstanceId;

    private String dispatchToken;

    private String mutexGroup;

    /** 失联节点 id（账本扣回） */
    private Long nodeId;

    private Long queueId;
}
