package com.flowops.modules.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/** 告警规则新建/编辑入参（CONTRACT §9；scope 展平为 projectId/clusterId —— README-M4 O-66）。 */
@Data
public class AlertRuleUpsertRequest {

    @NotBlank(message = "ruleName 必填")
    @Size(max = 128, message = "ruleName 最长 128")
    private String ruleName;

    /** 必须是 DDL CHECK 的 9 个事件类型之一（文档与 CONTRACT §9 不一致处以 DDL 为准） */
    @NotBlank(message = "eventType 必填")
    private String eventType;

    /** 按 event_type 定义的条件（null 按 {}） */
    private Map<String, Object> condition;

    /** 渠道类型列表（EMAIL/WECOM/WEBHOOK/DINGTALK） */
    private List<String> channels;

    /** 接收人列表（email 用） */
    private List<String> receivers;

    /** 抑制窗口（分钟），null 按 30 */
    private Integer suppressWindowMinutes;

    /** 项目业务编号 PRJ-xxxx（null = 全局） */
    private String projectId;

    /** 集群业务编号 CL-xxxx（null = 全局） */
    private String clusterId;
}
