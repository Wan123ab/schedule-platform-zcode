package com.flowops.modules.alert.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 规则启停入参（CONTRACT §9 PUT /alert-rules/{ruleId}/enabled）。 */
@Data
public class AlertEnabledRequest {

    @NotNull(message = "enabled 必填")
    private Boolean enabled;
}
