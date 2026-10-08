package com.flowops.executor.protocol;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

/**
 * 执行指令（D-23：下发语义 at-least-once，
 * 幂等键 = (stepInstanceId, attemptNo, dispatchToken) 三元组，docs/06 §4.6）。
 */
@Data
@Builder
public class ExecuteCommand {

    /** 步骤实例业务编号 SI-xxxx */
    private String stepInstanceId;

    /** 第几次尝试（1 起） */
    private Integer attemptNo;

    /** 调度器生成的下发令牌（防重复派发） */
    private String dispatchToken;

    private String machineIp;

    /** SSH / WINRM / AGENT */
    private String connectType;

    private String username;

    /** 解密后的凭据材料，仅内存持有、用完即弃（docs/03 §4.1） */
    @ToString.Exclude
    private transient String secretMaterial;

    /** 解析后的完整命令（M1 直传 start_command，变量 6 层解析随 M3） */
    private String resolvedCommand;

    private String workDir;

    /** 超时秒数；到点由执行侧终止进程 */
    private Integer timeoutSeconds;

    /** 成功退出码集合（PRD §12.4-5），默认 {0} */
    private java.util.List<Integer> successCodes;
}
