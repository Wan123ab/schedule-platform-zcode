package com.flowops.modules.task.dto;

import lombok.Data;

import java.util.List;

/**
 * 重跑失败步骤入参（CONTRACT §7：stepInstanceIds 缺省 = 全部失败步骤及其下游）。
 */
@Data
public class RerunFailedRequest {

    /** 指定要重跑的步骤实例业务编号（SI-xxxx）；缺省 = 全部 FAILED/TIMEOUT 步骤 + 下游 */
    private List<String> stepInstanceIds;
}
