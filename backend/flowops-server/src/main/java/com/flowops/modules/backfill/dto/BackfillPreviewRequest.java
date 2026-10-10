package com.flowops.modules.backfill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 回填预览/提交公共入参（CONTRACT §8；提交额外带 confirmedPreviewHash）。
 * 查询串不做键转换的既定口径只影响 GET；本 DTO 是 POST body（走 deepSnake）。
 */
@Data
public class BackfillPreviewRequest {

    /** 工作流业务编号 WF-xxxx */
    @NotNull(message = "workflowId 必填")
    private String workflowId;

    @NotNull(message = "bizFrom 必填")
    private LocalDate bizFrom;

    @NotNull(message = "bizTo 必填")
    private LocalDate bizTo;

    /** 用户设定并发，缺省 1（PRD §10.11-2） */
    private Integer concurrency;

    /** 顺序执行，缺省 true */
    private Boolean sequential;

    /** 全批次统一的 runParams 覆盖（docs 未定义 per-date 结构，不自造 —— README-M4 O-58） */
    private Map<String, Object> overrides;
}
