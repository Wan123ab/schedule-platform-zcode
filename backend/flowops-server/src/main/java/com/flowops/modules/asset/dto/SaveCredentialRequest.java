package com.flowops.modules.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 凭据创建/更新入参。{@code secret} 是 write-only 字段：
 * 服务端立即加密入库，任何出参都不含它（docs/07 §6.2 明文永不出网）。
 */
@Data
public class SaveCredentialRequest {

    @NotBlank(message = "凭据名称必填")
    private String credentialName;

    @NotBlank(message = "凭据类型必填")
    @Pattern(regexp = "SSH_KEY|USER_PASSWORD|WINRM|TOKEN", message = "凭据类型不合法")
    private String credentialType;

    private String username;

    /** 明文仅此一处入网；更新/轮换时必填 */
    private String secret;

    private Long projectId;

    private OffsetDateTime expireAt;

    private String description;
}
