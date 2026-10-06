package com.flowops.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 任务状态（9 态，docs/05 §5 / 07 §3.5；M-08 补 STOPPING）。
 */
public enum TaskStatus {
    PENDING("PENDING"),
    SCHEDULING("SCHEDULING"),
    RUNNING("RUNNING"),
    STOPPING("STOPPING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    STOPPED("STOPPED"),
    TIMEOUT("TIMEOUT"),
    PARTIAL("PARTIAL");

    @EnumValue
    @JsonValue
    private final String code;

    TaskStatus(String code) {
        this.code = code;
    }

    @JsonCreator
    public static TaskStatus of(String code) {
        for (TaskStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + code);
    }
}
