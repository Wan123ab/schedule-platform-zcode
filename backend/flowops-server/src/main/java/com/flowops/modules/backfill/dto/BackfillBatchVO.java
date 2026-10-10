package com.flowops.modules.backfill.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 回填批次（CONTRACT §8：进度区列表 + 批次详情；外键一律业务编号，D-27）。
 */
@Data
public class BackfillBatchVO {

    /** 业务编号 BF-0001 */
    private String batchId;

    private String batchName;

    /** 工作流业务编号（D-27） */
    private String workflowId;

    private String workflowName;

    /** 项目业务编号（D-27） */
    private String projectId;

    private LocalDate bizFrom;

    private LocalDate bizTo;

    private Integer concurrency;

    private Boolean sequential;

    /** 收敛后实际并发 */
    private Integer effConc;

    /** RUNNING / PAUSED / DONE / FAILED / CANCELLED */
    private String status;

    private Integer total;

    private Integer success;

    private Integer failed;

    private Integer waiting;

    private String submitter;

    private OffsetDateTime submittedAt;

    private OffsetDateTime finishedAt;

    private Long durationMs;

    /** 失败明细（进度区，最近 N 条） */
    private List<Failure> failures;

    @Data
    public static class Failure {
        private LocalDate bizDate;
        private String taskId;
        private String reason;
    }
}
