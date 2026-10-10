package com.flowops.modules.alert.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 告警规则 VO（CONTRACT §9 / §6.10）。projectId/clusterId 出业务编号（D-27）。 */
@Data
public class AlertRuleVO {

    private String ruleId;

    private String ruleName;

    private String eventType;

    private Map<String, Object> condition;

    private List<String> channels;

    private List<String> receivers;

    private Integer suppressWindowMinutes;

    /** 项目业务编号 PRJ-xxxx（null = 全局） */
    private String projectId;

    /** 集群业务编号 CL-xxxx（null = 全局） */
    private String clusterId;

    private Boolean enabled;

    private String creator;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
