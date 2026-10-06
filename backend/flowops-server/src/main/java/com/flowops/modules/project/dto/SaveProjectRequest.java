package com.flowops.modules.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 项目创建/更新入参（docs/07 §8.1）。 */
@Data
public class SaveProjectRequest {

    @NotBlank(message = "项目名称必填")
    @Size(max = 128, message = "项目名称最长 128 字符")
    private String projectName;

    @Size(max = 2000, message = "描述最长 2000 字符")
    private String description;

    /** 项目级默认参数（继承链第 2 层），键值对形式入库 JSONB */
    private java.util.Map<String, Object> defaultParams;
}
