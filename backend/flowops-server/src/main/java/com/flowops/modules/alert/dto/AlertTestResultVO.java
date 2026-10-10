package com.flowops.modules.alert.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 渠道测试结果（CONTRACT §9 POST /alert-channels/{type}/test）。 */
@Data
@AllArgsConstructor
public class AlertTestResultVO {

    private boolean ok;

    private long latencyMs;

    private String error;
}
