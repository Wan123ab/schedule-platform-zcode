package com.flowops.modules.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 停止任务入参（CONTRACT §7：stopReason ≥ 5 字符必填）。
 */
@Data
public class StopTaskRequest {

    @NotBlank(message = "stopReason 必填")
    @Size(min = 5, message = "停止原因至少 5 个字符")
    private String stopReason;
}
