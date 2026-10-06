package com.flowops.modules.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.auth.AppUser;
import com.flowops.domain.mapper.auth.AppUserMapper;
import com.flowops.modules.auth.dto.LoginResponse;
import com.flowops.modules.auth.manager.LoginAttemptManager;
import com.flowops.modules.auth.mapper.AuthQueryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 认证服务（docs/07 §5.1）：登录闭环 = 锁定预检（40101+lockedUntil）→ 凭据校验 →
 * 失败记账（5 次/15 分钟）→ 成功清账 + 会话签发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserMapper appUserMapper;
    private final AuthQueryMapper authQueryMapper;
    private final LoginAttemptManager loginAttemptManager;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(String username, String password, String loginIp) {
        // ① 锁定预检（在凭据校验之前：锁定期间连正确密码也拒绝，PRD §11.2 口径）
        OffsetDateTime lockedUntil = loginAttemptManager.lockedUntil(username);
        if (lockedUntil != null) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定",
                    java.util.Map.of("locked_until", lockedUntil.toString()));
        }

        AppUser user = appUserMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, username)
                .eq(AppUser::getDeleted, false));

        // 统一失败口径，不区分「用户不存在」与「密码错误」（防用户名枚举）
        if (user == null || !"ENABLED".equals(user.getStatus())
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            log.info("登录失败 username={} ip={}", username, loginIp);
            loginAttemptManager.onFailure(username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        List<String> permissions = authQueryMapper.selectPermissionCodes(user.getId());
        List<String> roles = authQueryMapper.selectRoleCodes(user.getId());
        List<String> scopeTypes = authQueryMapper.selectScopeTypes(user.getId());

        StpUtil.login(user.getId());
        // 权限点列表写入会话，请求期由 UserContextInterceptor 读取，避免每请求查库
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("displayName", user.getDisplayName());
        StpUtil.getSession().set("permissions", permissions);

        user.setLastLoginAt(OffsetDateTime.now());
        user.setLastLoginIp(loginIp);
        appUserMapper.updateById(user);
        loginAttemptManager.onSuccess(username);

        return LoginResponse.builder()
                .tokenName(StpUtil.getTokenName())
                .tokenValue(StpUtil.getTokenValue())
                .user(LoginResponse.UserInfoVO.builder()
                        .userId(user.getUserId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .roles(roles)
                        .scopeTypes(scopeTypes)
                        .build())
                .permissions(permissions)
                .build();
    }

    public void logout() {
        StpUtil.logout();
    }
}
