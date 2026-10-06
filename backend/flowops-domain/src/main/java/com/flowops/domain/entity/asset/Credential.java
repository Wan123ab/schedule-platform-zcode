package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 凭据（docs/05 §3.3 credential）—— 全平台最敏感实体。
 *
 * <p><b>三条铁律（docs/03 §4.1 / docs/07 §6.2）</b>：</p>
 * <ul>
 *   <li>{@code secret_encrypted} 是 AES-256-GCM 密文，只在执行下发时解密且只在内存（用完即弃）；</li>
 *   <li>明文永不出网：任何 VO 只带 {@code secret_fingerprint}（SHA-256 后 4 位，展示 ****xxxx）；</li>
 *   <li>{@code ref_count > 0} 禁删：删除判定用<b>实时 COUNT</b>（docs/05 §6.3：冗余计数绝不作业务判定唯一依据），
 *       ref_count 列只做展示与对账。</li>
 * </ul>
 */
@Data
@TableName("credential")
public class Credential {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 CR-0001 */
    private String credentialId;

    private String credentialName;

    /** SSH_KEY / USER_PASSWORD / WINRM / TOKEN */
    private String credentialType;

    private String username;

    /** AES-256-GCM 密文（Base64），主密钥来自 FLOWOPS_CRED_MASTER_KEY（M-06） */
    private String secretEncrypted;

    /** SHA-256(secret) 全量 hex（展示侧取后 4 位掩码） */
    private String secretFingerprint;

    /** 项目级（非空）或平台级（NULL） */
    private Long projectId;

    /** 引用计数（冗余展示；删除判定用实时 COUNT） */
    private Integer refCount;

    /** VALID / EXPIRING / EXPIRED / REVOKED */
    private String status;

    private OffsetDateTime lastRotatedAt;

    private OffsetDateTime expireAt;

    private String description;

    private String creator;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Integer version;

    private Boolean deleted;
}
