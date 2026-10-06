package com.flowops.domain.dto.query;

import lombok.Data;

/**
 * 工作流并发配置读模型（docs/05 §3.4 workflow 表的并发两列投影）。
 * 只投影 ConcurrencyGuard 需要的列——不拉整行实体，查询面最小化。
 */
@Data
public class WorkflowConcurrencyConfig {

    private Long workflowId;

    /** FORBID / ALLOW / QUEUE（PRD §12.3；MyBatis 默认枚举处理器按 name 映射，与 code 一致） */
    private String concurrencyPolicy;

    /** 工作流级最大并行实例数 */
    private Integer maxParallelRuns;
}
