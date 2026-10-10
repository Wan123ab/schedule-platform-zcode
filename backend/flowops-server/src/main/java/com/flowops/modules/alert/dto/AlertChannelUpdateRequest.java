package com.flowops.modules.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 渠道更新入参（CONTRACT §9 PUT /alert-channels/{type}；upsert 语义 —— 渠道表初始为空）。 */
@Data
public class AlertChannelUpdateRequest {

    @NotBlank(message = "endpoint 必填")
    @Size(max = 512, message = "endpoint 最长 512")
    private String endpoint;

    /** 渠道密钥（明文入参，入库前 AES-256-GCM 加密；null = 不更新密钥） */
    private String secret;
}
