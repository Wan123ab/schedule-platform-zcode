package com.flowops.modules.alert.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 告警记录 VO（CONTRACT §9 GET /alert-history：alert[]{ruleId,eventType,targetId,firedAt,channels[],result}）。 */
@Data
public class AlertRecordVO {

    private String alertId;

    /** 规则业务编号 AR-xxxx（规则已删时为 null，记录保留） */
    private String ruleId;

    private String eventType;

    private String level;

    private String targetType;

    /** 多态目标：这里出的是行 id（docs/05 R20 无业务编号表 —— 任务目标在 S5 汇总时按需翻译） */
    private Long targetId;

    private String targetName;

    private String projectId;

    private String clusterId;

    private String content;

    private List<String> channels;

    private List<Map<String, Object>> deliverResult;

    /** SENT / PARTIAL / FAILED / SUPPRESSED */
    private String result;

    private OffsetDateTime firedAt;
}
