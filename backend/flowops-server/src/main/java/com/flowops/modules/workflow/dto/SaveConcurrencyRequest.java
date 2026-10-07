package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 工作流并发设置入参（CONTRACT §6.1 {@code PUT /workflows/{id}/concurrency}）。
 *
 * <p>单独一个端点而不是并入 {@code PUT /workflows/{id}}：并发配置是 DAG 规则 8
 * 的校验对象（"必须声明并发控制配置"，PRD §10.7），改它会影响"能否发布"，
 * 因此有独立的审计动作 {@code UPDATE_CONCURRENCY}。</p>
 */
@Data
public class SaveConcurrencyRequest {

    /** FORBID（跳过本次触发）/ ALLOW（并行执行）/ QUEUE（排队等待），对齐 DDL 的 CHECK */
    @NotBlank(message = "并发策略必填")
    @Pattern(regexp = "FORBID|ALLOW|QUEUE", message = "并发策略取值非法")
    private String concurrencyPolicy;

    /** 同一工作流允许同时运行的任务数；默认 1（PRD §10.7 默认值表） */
    @NotNull(message = "最大并行数必填")
    @Min(value = 1, message = "最大并行数必须大于等于 1")
    private Integer maxParallelRuns;
}
