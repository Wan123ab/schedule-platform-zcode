package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 触发类型（5 种，docs/05 §5：CONTRACT §0.4 + 回填扩展）。 */
public enum TriggerType {
    MANUAL("MANUAL"),
    CRON("CRON"),
    API("API"),
    EVENT("EVENT"),
    BACKFILL("BACKFILL");

    @EnumValue
    @JsonValue
    private final String code;

    TriggerType(String code) {
        this.code = code;
    }

    @JsonCreator
    public static TriggerType of(String code) {
        for (TriggerType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知触发类型: " + code);
    }
}
