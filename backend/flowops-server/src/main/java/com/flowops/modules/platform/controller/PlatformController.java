package com.flowops.modules.platform.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台级接口（docs/07 §6.7 第 19 页 + §5.4 映射）。
 *
 * <p><b>M0 切面链走查端点</b>（docs/09 M0 DoD-4）：
 * {@code POST /platform/view-mode} 串联
 * {@code @RequiresPermission("schedule:platform:view:ops")} →
 * {@code @Audited(action = "SWITCH_OPS_VIEW", targetType = "USER")} →
 * 响应 {@code {code, message, data, trace_id}}。SWITCH_OPS_VIEW 属必审动作（docs/07 §7.3）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/platform")
public class PlatformController {

    /** 平台健康度（第 19 页，M0 返回骨架数据；M5 接入全量指标）。 */
    @GetMapping("/health")
    @RequiresPermission("schedule:platform:health")
    @DataScope("ALL")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of(
                "scheduler", Map.of(
                        "leader_id", "unavailable",
                        "is_leader", false,
                        "last_tick_at", OffsetDateTime.now().toString(),
                        "tick_interval_ms", 1000,
                        "standby_count", 0),
                "db", Map.of("pool_active", 0, "pool_idle", 0, "pool_max", 10, "slow_query_count_1m", 0),
                "redis", Map.of("connected", true, "queue_depth", 0),
                "nodes", Map.of("online", 0, "offline", 0, "total", 0, "online_rate", 100),
                "queues", List.of(),
                "today", Map.of("dispatched_count", 0, "failed_count", 0, "log_ingest_bytes", 0),
                "recent_alerts", List.of()));
    }

    /** 切换运维/业务视图（PRD §11.3；切换动作本身必审）。 */
    public record ViewModeRequest(String viewMode) {}

    @PostMapping("/view-mode")
    @RequiresPermission("schedule:platform:view:ops")
    @DataScope("ALL")
    @Audited(action = "SWITCH_OPS_VIEW", targetType = "USER", targetIdExpr = "username")
    public ApiResult<Map<String, Object>> switchViewMode(@RequestBody ViewModeRequest request) {
        if (!"business".equals(request.viewMode()) && !"ops".equals(request.viewMode())) {
            throw new com.flowops.common.exception.BizException(
                    com.flowops.common.api.ErrorCode.PARAM_INVALID, "viewMode 仅允许 business|ops");
        }
        UserContext user = UserContext.get();
        log.info("视图切换 user={} viewMode={}", user != null ? user.getUsername() : null, request.viewMode());
        return ApiResult.ok(Map.of("viewMode", request.viewMode()));
    }
}
