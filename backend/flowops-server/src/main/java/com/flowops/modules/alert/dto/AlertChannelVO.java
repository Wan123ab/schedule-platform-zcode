package com.flowops.modules.alert.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/** 通知渠道 VO（CONTRACT §9；secret 永不出网 —— M-06 同凭据口径）。 */
@Data
public class AlertChannelVO {

    private String channelType;

    private String endpoint;

    private Boolean enabled;

    private Boolean verified;

    private OffsetDateTime lastTestAt;

    private String lastTestResult;
}
