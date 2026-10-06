package com.flowops.domain.dto.query;

import lombok.Data;

/** 项目并发额度读模型（docs/05 §3.2 project 表的额度列投影，PRD §11.4）。 */
@Data
public class ProjectConcurrencyConfig {

    private Long projectId;

    /** 项目级最大并发任务数 */
    private Integer maxConcurrentTasks;
}
