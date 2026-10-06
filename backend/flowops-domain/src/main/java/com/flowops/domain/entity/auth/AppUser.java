package com.flowops.domain.entity.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 平台用户（docs/05 §3.1 app_user）。
 */
@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String username;

    private String displayName;

    /** BCrypt：$2a$10$... */
    private String passwordHash;

    private Long tenantId;

    private String email;

    private String phone;

    /** ENABLED / DISABLED / LOCKED */
    private String status;

    private Integer loginFailCount;

    private OffsetDateTime lockedUntil;

    private OffsetDateTime lastLoginAt;

    private String lastLoginIp;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private Integer version;

    private Boolean deleted;
}
