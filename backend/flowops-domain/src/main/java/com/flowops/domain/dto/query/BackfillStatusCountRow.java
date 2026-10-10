package com.flowops.domain.dto.query;

import lombok.Data;

/** 批次任务状态计数行（docs/06 §12.1 ④：批次进度刷新）。 */
@Data
public class BackfillStatusCountRow {

    private String status;

    private Long cnt;
}
