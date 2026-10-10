package com.flowops.modules.backfill.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 回填预览结果（CONTRACT §8；不落库）。
 *
 * <p>{@code plannedTaskId} 是<b>预览占位号</b>（"P-0001" 序号），不是真实任务编号 ——
 * 任务编号在投放时由 {@code IdGen} 发号，预览阶段不存在任务行（README-M4 O-57）。</p>
 */
@Data
public class BackfillPreviewVO {

    private List<PlannedTask> tasks;

    /** 越窗日期（早于 today-180 或晚于昨天），不生成任务（CONTRACT §8-4） */
    private List<LocalDate> skippedDates;

    private long plannedCount;

    private long conflictCount;

    /** 收敛后实际并发（CONTRACT §8-2 三层收敛） */
    private int effConc;

    /** 策略冲突提示（sequential=false 且 FORBID 收敛时非空，前端渲染提示） */
    private PolicyConflict policyConflict;

    /** 预览配置的 SHA-256（提交时原样带回 confirmedPreviewHash） */
    private String previewHash;

    @Data
    public static class PlannedTask {
        private LocalDate bizDate;
        /** 预览占位号 P-0001（README-M4 O-57） */
        private String plannedTaskId;
        /** 计划投递时刻（业务日期 00:00，口径见 README-M4 O-56） */
        private String plannedAt;
        /** 同工作流 + 同业务日期 + 投递时刻 ±30 分钟命中 → {taskId, status} */
        private Map<String, Object> conflict;
    }

    @Data
    public static class PolicyConflict {
        private String policy;
        private Integer maxParallelRuns;
        private int effConc;
    }
}
