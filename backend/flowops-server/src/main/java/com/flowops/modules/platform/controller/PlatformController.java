package com.flowops.modules.platform.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
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

    // ── 平台健康度（GET /platform/health）不在这里 ──────────────────
    // M0 曾在此放过一个"骨架"实现，M4-S5 交付了真实实现
    // （modules/dashboard/controller/PlatformHealthController），
    // 于是同一路径出现了两个处理器方法 —— Spring 在装配 requestMappingHandlerMapping
    // 时抛 Ambiguous mapping，**进程根本起不来**。
    // 该问题一直到 2026-10-25 才被发现（O-74）：此前没有任何 @SpringBootTest，
    // 而分层单测不起 Spring 上下文，永远不会撞到这条装配期校验。
    // 删骨架留真实实现：骨架返回的是硬编码假数据，留着只会让"健康页显示 100%"
    // 变成没人敢信的数字。

    /** 切换运维/业务视图（PRD §11.3；切换动作本身必审）。 */
    public record ViewModeRequest(String viewMode) {}

    @PostMapping("/view-mode")
    @RequiresPermission("schedule:platform:view:ops")
    @DataScope("ALL")
    @Audited(action = "SWITCH_OPS_VIEW", targetType = "USER", targetIdExpr = "username")
    public ApiResult<Map<String, Object>> switchViewMode(@RequestBody ViewModeRequest request) {
        if (!"business".equals(request.viewMode()) && !"ops".equals(request.viewMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "viewMode 仅允许 business|ops");
        }
        UserContext user = UserContext.get();
        log.info("视图切换 user={} viewMode={}", user != null ? user.getUsername() : null, request.viewMode());
        return ApiResult.ok(Map.of("viewMode", request.viewMode()));
    }
}
