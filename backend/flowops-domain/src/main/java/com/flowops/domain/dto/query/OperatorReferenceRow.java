package com.flowops.domain.dto.query;

import lombok.Data;

/**
 * 算子版本被引用行（GET /operator-versions/{versionId}/references）。
 *
 * <p>返回的是"工作流 + 步骤"的定位信息而非内部主键：前端要渲染成可点进去的链接，
 * 且内部 bigint 主键不出网（D-27）。</p>
 */
@Data
public class OperatorReferenceRow {

    /** 工作流业务编号 WF-0001 */
    private String workflowId;

    private String workflowName;

    /** 引用该版本的步骤名（同一工作流可多步骤引用，故一行一个步骤） */
    private String stepName;

    /** 引用所在版本号 v1/v2 —— 判断"是草稿引用还是已发布引用"的依据 */
    private String versionNo;

    /** 该工作流版本的发布状态 DRAFT / PUBLISHED / OFFLINE */
    private String publishStatus;
}
