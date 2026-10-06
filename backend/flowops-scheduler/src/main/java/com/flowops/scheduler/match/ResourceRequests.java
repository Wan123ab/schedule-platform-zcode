package com.flowops.scheduler.match;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 资源申请 JSON 解析（管线派发与启动恢复共用 —— DRY，docs/10）。
 * 防御口径（docs/06 §10.2 ④）：缺失/解析失败按 0 计并告警——宁可少算，不可因此拒绝所有调度。
 */
@Slf4j
public final class ResourceRequests {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ResourceRequests() {
    }

    public static ReservedLedger.Resource parse(String json) {
        if (json == null || json.isBlank()) {
            return ReservedLedger.Resource.ZERO;
        }
        try {
            Map<?, ?> map = MAPPER.readValue(json, Map.class);
            return new ReservedLedger.Resource(
                    number(map.get("cpu")), number(map.get("gpu")),
                    (long) number(map.get("memory")), (long) number(map.get("disk")));
        } catch (Exception e) {
            log.warn("资源申请解析失败按 0 计（防御口径 §10.2 ④）: {}", json);
            return ReservedLedger.Resource.ZERO;
        }
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
