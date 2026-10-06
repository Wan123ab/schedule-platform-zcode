package com.flowops.domain.dto.query;

import lombok.Data;

/** 提交期版本解析结果（TaskSubmitService 专用投影）。 */
@Data
public class SubmitVersionRow {

    private Long versionId;
    /** v1 / v2（任务快照冗余列 workflow_version 的取值） */
    private String versionNo;
    private String workflowName;
    private Long projectId;
}
