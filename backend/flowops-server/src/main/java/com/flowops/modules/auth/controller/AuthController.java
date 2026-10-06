package com.flowops.modules.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.flowops.common.annotation.Audited;
import com.flowops.common.api.ApiResult;
import com.flowops.common.context.UserContext;
import com.flowops.modules.auth.dto.LoginRequest;
import com.flowops.modules.auth.dto.LoginResponse;
import com.flowops.modules.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（docs/07 §5.4：POST /auth/login、/auth/logout、GET /auth/me 公开/登录即可）。
 * 登录属必审动作（docs/07 §7.3：LOGIN）。
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/auth/login")
    @Audited(action = "LOGIN", targetType = "USER", targetIdExpr = "#request.username")
    public ApiResult<LoginResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return ApiResult.ok(authService.login(request.getUsername(), request.getPassword(), http.getRemoteAddr()));
    }

    @PostMapping("/auth/logout")
    public ApiResult<Void> logout() {
        authService.logout();
        return ApiResult.ok();
    }

    @GetMapping("/auth/me")
    public ApiResult<UserContext> me() {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            return ApiResult.fail(com.flowops.common.api.ErrorCode.UNAUTHORIZED);
        }
        return ApiResult.ok(ctx);
    }
}
