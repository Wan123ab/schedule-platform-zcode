package com.flowops.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录入参（docs/07 §8.1：入参 XxxRequest）。 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名必填")
    private String username;

    @NotBlank(message = "密码必填")
    private String password;
}
