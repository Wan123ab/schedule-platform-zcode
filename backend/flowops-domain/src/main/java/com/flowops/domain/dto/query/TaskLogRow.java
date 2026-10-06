package com.flowops.domain.dto.query;

import lombok.Data;

import java.time.OffsetDateTime;

/** 日志行（task_log 分区表的写/读投影；docs/05 §7.2）。 */
@Data
public class TaskLogRow {

    private Long taskStepId;

    private Long taskId;

    /** 全局递增序号（offset 续传依据，M-09 同源：Redis INCR 严格单调） */
    private Long seq;

    /** EOF=stdout / ERR=stderr（PRD §13.1-5） */
    private String stream;

    private String content;

    private OffsetDateTime logTime;
}
