package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 工作流状态（4 态，docs/05 §5）。 */
public enum WorkflowStatus {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    DISABLED("DISABLED"),
    ARCHIVED("ARCHIVED");

    @EnumValue
    @JsonValue
    private final String code;

    WorkflowStatus(String code) {
        this.code = code;
    }

    @JsonCreator
    public static WorkflowStatus of(String code) {
        for (WorkflowStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知工作流状态: " + code);
    }
}
