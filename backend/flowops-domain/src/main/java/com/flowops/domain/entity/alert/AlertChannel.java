package com.flowops.domain.entity.alert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 通知渠道配置（docs/05 §3.7 alert_channel，DDL 唯一真源）。
 *
 * <p><b>channel_type UNIQUE</b>：每类型一行配置（EMAIL/WECOM/WEBHOOK/DINGTALK），
 * 表初始为空 —— PUT /alert-channels/{type} 是 <b>upsert</b> 语义（V5 只种了规则、没种渠道）。</p>
 *
 * <p><b>无 deleted 列</b>（DDL 没有）—— 渠道不删除，只停用（enabled=false）。</p>
 */
@Data
@TableName(value = "alert_channel", autoResultMap = true)
public class AlertChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelType;

    /** 投递目标：webhook URL / SMTP 标识等 */
    private String endpoint;

    /** 渠道密钥（同凭据加密策略 M-06，AES-256-GCM，明文不出库） */
    private String secretEncrypted;

    private Boolean enabled;

    private Boolean verified;

    private OffsetDateTime lastTestAt;

    private String lastTestResult;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @Version
    private Integer version;
}
