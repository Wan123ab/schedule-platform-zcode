package com.flowops.domain.dto.query;

import lombok.Data;

/** 工作流连线行（DagGraph 装配输入）。 */
@Data
public class EdgeRow {

    private Long sourceStepId;
    private Long targetStepId;
}
