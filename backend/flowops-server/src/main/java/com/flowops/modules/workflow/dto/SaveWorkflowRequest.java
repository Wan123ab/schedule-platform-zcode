package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工作流新建/编辑入参（CONTRACT §6.1 {@code POST/PUT /workflows}）。
 *
 * <p>发布（{@code POST /workflows/{id}/publish}）不在本类里：它只带 {@code versionId}，
 * 且要过 DAG 全量校验（42213）。</p>
 */
@Data
public class SaveWorkflowRequest {

    @NotBlank(message = "工作流名称必填")
    @Size(max = 128, message = "工作流名称最长 128 字符")
    private String workflowName;

    /** 所属项目业务编号 PRJ-xxxx（内部主键不出网，D-27） */
    @NotBlank(message = "所属项目必填")
    private String projectId;

    @Size(max = 2000, message = "描述最长 2000 字符")
    private String description;
}
