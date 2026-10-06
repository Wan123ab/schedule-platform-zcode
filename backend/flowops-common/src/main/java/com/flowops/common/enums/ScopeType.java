package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 数据范围（D-19，docs/07 §5.3 唯一命名口径）。 */
public enum ScopeType {
    ALL("ALL"),
    AUTHORIZED_CLUSTER("AUTHORIZED_CLUSTER"),
    PROJECT("PROJECT"),
    SELF_CREATED("SELF_CREATED"),
    NONE("NONE");

    @EnumValue
    @JsonValue
    private final String code;

    ScopeType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ScopeType of(String code) {
        for (ScopeType s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知数据范围: " + code);
    }
}
