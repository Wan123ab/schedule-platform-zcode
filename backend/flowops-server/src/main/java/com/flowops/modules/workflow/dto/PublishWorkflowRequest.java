package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发布工作流入参（CONTRACT §6.1 {@code POST /workflows/{workflowId}/publish}）。
 *
 * <p>发布必须指明<b>发布哪一版</b>：工作流下同时可能有草稿与多个历史版本，
 * "发布最新的"在并发编辑下是不可解释的（docs 也从未这样定义）。</p>
 */
@Data
public class PublishWorkflowRequest {

    /** 版本业务编号 WFV-xxxx-xx */
    @NotBlank(message = "版本编号必填")
    private String versionId;
}
