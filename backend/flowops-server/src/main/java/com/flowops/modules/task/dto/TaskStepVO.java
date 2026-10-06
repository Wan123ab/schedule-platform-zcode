package com.flowops.modules.task.dto;

import java.time.OffsetDateTime;

/** 步骤实例出参（M1 最小字段集；DAG 着色/日志/诊断随 M4 扩展）。 */
public record TaskStepVO(
        /** 物理主键（日志 WebSocket 通道与干预接口的定位键；业务编号不含它，必须显式下发） */
        Long rowId,
        String stepInstanceId,
        String stepName,
        Integer stepIndex,
        String status,
        String machineIp,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long durationMs,
        Integer exitCode,
        Integer retryCount) {
}
