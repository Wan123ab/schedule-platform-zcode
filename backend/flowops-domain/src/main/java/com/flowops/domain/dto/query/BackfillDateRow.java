package com.flowops.domain.dto.query;

import lombok.Data;

/** 回填冲突命中行（docs/06 §12.3：conflict{taskId, status}）。 */
@Data
public class BackfillDateRow {

    /** 命中任务的业务编号 TASK-xxxx */
    private String taskId;

    private String status;
}
