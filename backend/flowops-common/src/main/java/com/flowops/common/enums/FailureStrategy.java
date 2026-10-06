package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 失败策略（PRD §10.8）。 */
public enum FailureStrategy {
    TERMINATE("TERMINATE"),
    RETRY("RETRY");

    @EnumValue
    @JsonValue
    private final String code;

    FailureStrategy(String code) {
        this.code = code;
    }

    @JsonCreator
    public static FailureStrategy of(String code) {
        for (FailureStrategy s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知失败策略: " + code);
    }
}
