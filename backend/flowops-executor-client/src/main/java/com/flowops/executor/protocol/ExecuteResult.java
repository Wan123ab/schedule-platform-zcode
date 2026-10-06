package com.flowops.executor.protocol;

import lombok.Builder;
import lombok.Data;

/** 执行结果回执（退出码 + 资源峰值 + 输出摘要）。 */
@Data
@Builder
public class ExecuteResult {

    private String stepInstanceId;

    private Integer attemptNo;

    private String dispatchToken;

    /** null = 进程未能启动（连接失败等）——调度侧按 DISPATCH_FAIL 回退，而非执行失败 */
    private Integer exitCode;

    /** 纳秒 */
    private Long durationNanos;

    /** {cpuPeak, gpuPeak, memoryPeak, diskDelta}，供资源画像（PRD §13.4） */
    private java.util.Map<String, Object> resourceActual;

    /** 已提取的输出变量（按 operator_output_decl 采集，M3 接入） */
    private java.util.Map<String, String> outputVars;

    private String failReason;
}
