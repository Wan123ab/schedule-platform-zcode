package com.flowops.modules.workflow.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工作流当前版本概要（CONTRACT §6.1：{@code GET /workflows/{id}} 返回
 * {@code workflow} + {@code currentVersion} 概要）。
 *
 * <p>只带"第一屏要看"的字段；DAG 全量走 {@code GET /workflow-versions/{versionId}}。
 * 详情页先渲染概要再做一次请求拉图，避免首屏为一个 100 节点的 JSONB 卡住。</p>
 */
@Data
public class WorkflowVersionBrief {

    /** 业务编号 WFV-<工作流数字段>-<两位序号> */
    private String versionId;

    /** v1 / v2 …（工作流内单调递增，永不复用） */
    private String versionNo;

    /** DRAFT / PUBLISHED / ARCHIVED */
    private String publishStatus;

    private Integer stepCount;

    private String publisher;

    private OffsetDateTime publishedAt;
}
