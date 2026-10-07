package com.flowops.modules.workflow.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 触发器响应（docs/07 §6.4）。外键一律业务编号（D-27）：{@code workflowId} = WF-xxxx。
 */
@Data
public class TriggerVO {

    private String triggerId;

    /** 父工作流业务编号（内部主键不出网，D-27） */
    private String workflowId;

    private String triggerName;

    /** MANUAL / CRON */
    private String triggerType;

    private String cronExpression;

    private Integer periodSeconds;

    private String timezone;

    private String effectiveStart;

    private String effectiveEnd;

    private Boolean enabled;

    private Map<String, Object> runParams;

    private Boolean catchUpEnabled;

    private Integer catchUpMaxTimes;

    private OffsetDateTime nextFireTime;

    private OffsetDateTime lastFireTime;

    private String lastFireStatus;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
