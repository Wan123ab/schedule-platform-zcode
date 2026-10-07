package com.flowops.modules.asset.dto;

import lombok.Data;

/**
 * 算子版本被引用项（GET /operator-versions/{versionId}/references）。
 *
 * <p>只出业务编号与名称：前端要渲染成可点进去的链接，且内部 bigint 主键不出网（D-27）。</p>
 */
@Data
public class OperatorReferenceVO {

    /** 工作流业务编号 WF-0001 */
    private String workflowId;

    private String workflowName;

    /** 引用该版本的工作流版本号 v1/v2 —— 用于区分"草稿引用"与"已发布引用" */
    private String versionNo;

    /** 该工作流版本的发布状态 DRAFT / PUBLISHED / OFFLINE */
    private String publishStatus;

    /** 引用该版本的步骤名（同一工作流可多步骤引用，故一行一个步骤） */
    private String stepName;
}
