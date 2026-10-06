package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 并发策略（PRD §12.3）。 */
public enum ConcurrencyPolicy {
    FORBID("FORBID"),
    ALLOW("ALLOW"),
    QUEUE("QUEUE");

    @EnumValue
    @JsonValue
    private final String code;

    ConcurrencyPolicy(String code) {
        this.code = code;
    }

    @JsonCreator
    public static ConcurrencyPolicy of(String code) {
        for (ConcurrencyPolicy p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知并发策略: " + code);
    }
}
