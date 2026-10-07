package com.flowops.modules.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 算子新建/编辑入参（docs/07 §5.4 {@code POST/PUT /operators*}）。
 */
@Data
public class SaveOperatorRequest {

    @NotBlank(message = "算子名称必填")
    @Size(max = 128, message = "算子名称最长 128 字符")
    private String operatorName;

    /** JAR / PYTHON / SHELL / BAT / EXE / CUSTOM（与 DDL CHECK 对齐） */
    @NotBlank(message = "算子类型必填")
    @Pattern(regexp = "JAR|PYTHON|SHELL|BAT|EXE|CUSTOM", message = "算子类型取值非法")
    private String operatorType;

    /** 所属项目业务编号 PRJ-xxxx（内部主键不出网，D-27） */
    @NotBlank(message = "所属项目必填")
    private String projectId;

    private String description;

    /** 仅编辑时可改；ENABLED / DISABLED */
    @Pattern(regexp = "ENABLED|DISABLED", message = "状态取值非法")
    private String status;
}
