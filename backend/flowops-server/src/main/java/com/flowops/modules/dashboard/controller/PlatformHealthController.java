package com.flowops.modules.dashboard.controller;

import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.dashboard.dto.PlatformHealthVO;
import com.flowops.modules.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台健康度接口（M4-S5，CONTRACT §11.1 / docs/07 §6.7）。
 *
 * <p>权限点 45（docs/07 §5.2）：平台管理员、运维；前端 10s 轮询。
 * scheduler 段的可得性口径（选主锁推导）见 {@code PlatformHealthVO} 类注与 O-71。</p>
 */
@RestController
@RequiredArgsConstructor
public class PlatformHealthController {

    private final DashboardService dashboardService;

    @GetMapping("/platform/health")
    @RequiresPermission("schedule:platform:health")
    public ApiResult<PlatformHealthVO> health() {
        return ApiResult.ok(dashboardService.platformHealth());
    }
}
