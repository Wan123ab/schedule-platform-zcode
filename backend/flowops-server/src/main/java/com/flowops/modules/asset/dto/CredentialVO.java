package com.flowops.modules.asset.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 凭据出参 —— <b>永不包含 secret / secret_encrypted</b>（docs/07 §6.2）。
 * 指纹仅后 4 位可见（****xxxx），凭"记住的样子"辅助辨认，不构成攻击面。
 */
@Data
public class CredentialVO {

    private String credentialId;
    private String credentialName;
    private String credentialType;
    private String username;
    /** 仅后 4 位，如 ****a1b2 */
    private String secretFingerprint;

    /** 归属项目的**业务编号**（PRJ-xxxx）；平台级凭据为 null（内部 Long 主键不出网） */
    private String projectId;

    /** 归属项目名（服务层填充；平台级为 null） */
    private String projectName;

    private Integer refCount;
    private String status;
    private OffsetDateTime lastRotatedAt;
    private OffsetDateTime expireAt;
    private String description;
}
