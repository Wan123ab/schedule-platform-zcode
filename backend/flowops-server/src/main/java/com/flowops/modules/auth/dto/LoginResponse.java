package com.flowops.modules.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 登录出参：token + 用户信息 + 权限点列表（D-16：前端按权限点判定，不硬编码角色）。 */
@Data
@Builder
public class LoginResponse {

    private String tokenName;
    private String tokenValue;
    private UserInfoVO user;
    private List<String> permissions;

    @Data
    @Builder
    public static class UserInfoVO {
        private String userId;
        private String username;
        private String displayName;
        private List<String> roles;
        private List<String> scopeTypes;
    }
}
