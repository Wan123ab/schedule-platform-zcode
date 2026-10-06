package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 步骤实例状态（11 态，docs/07 §3.5；SKIPPED 预留一期不产生）。
 */
public enum StepStatus {
    NOT_STARTED("NOT_STARTED"),
    WAITING_DEPENDENCY("WAITING_DEPENDENCY"),
    WAITING_RESOURCE("WAITING_RESOURCE"),
    SCHEDULING("SCHEDULING"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    RETRYING("RETRYING"),
    SKIPPED("SKIPPED"),
    STOPPED("STOPPED"),
    TIMEOUT("TIMEOUT");

    @EnumValue
    @JsonValue
    private final String code;

    StepStatus(String code) {
        this.code = code;
    }

    @JsonCreator
    public static StepStatus of(String code) {
        for (StepStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知步骤状态: " + code);
    }

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == STOPPED || this == TIMEOUT;
    }
}
