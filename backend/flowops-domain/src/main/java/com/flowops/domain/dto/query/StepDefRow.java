package com.flowops.domain.dto.query;

import lombok.Data;

/** 工作流步骤定义行（DagGraph 装配输入，docs/06 §7.1）。 */
@Data
public class StepDefRow {

    /** workflow_step.id —— 图节点 id */
    private Long stepId;
    private String stepName;
    /** TASK / NOTE（NOTE 在图构建期剔除） */
    private String stepType;
}
