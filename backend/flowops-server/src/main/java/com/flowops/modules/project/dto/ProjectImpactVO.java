package com.flowops.modules.project.dto;

import java.util.List;

/** 项目停用影响面（docs/07 §6.1：PUT status 的前置统计，前端展示"受影响清单"）。 */
public record ProjectImpactVO(
        long workflowCount,
        long runningTaskCount,
        long memberCount,
        long triggerCount,
        List<String> blocking) {
}
