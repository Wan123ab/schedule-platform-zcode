package com.flowops.modules.asset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 执行节点创建/更新入参（docs/05 §3.3 executor_node）。
 *
 * <p><b>刻意不包含的字段</b>：{@code onlineStatus}/{@code lastHeartbeatAt}/{@code heartbeatMissCount}
 * （心跳链路的事实，人工不得改写）；{@code cpuUsed} 等实际用量（由执行器上报）。
 * 少了这些入参字段，"人工污染心跳"在编译期就不可能发生。</p>
 */
@Data
public class SaveExecutorNodeRequest {

    @NotBlank(message = "节点名称必填")
    private String executorNodeName;

    /** 业务编号 CL-0001；更新时以路径/现有归属为准 */
    private String clusterId;

    @NotBlank(message = "IP 必填")
    private String ip;

    @NotBlank(message = "操作系统类型必填")
    @Pattern(regexp = "LINUX|WINDOWS", message = "操作系统类型不合法")
    private String osType;

    @Pattern(regexp = "SSH|WINRM|AGENT", message = "连接方式不合法")
    private String connectType;

    /** 绑定凭据的**业务编号**（CR-xxxx）；服务层解析成内部主键（内部 id 不出网） */
    private String credentialId;

    /** 标签（PG text[]） */
    private String[] tags;

    /** 预留账本硬上限；不填 = 不限制 */
    @Min(value = 1, message = "步骤并发上限至少为 1")
    @Max(value = 1000, message = "步骤并发上限过大")
    private Integer maxConcurrentSteps;

    /** 节点标称容量（用于余量计算；实际用量由执行器上报） */
    private BigDecimal cpuTotal;

    private BigDecimal gpuTotal;

    /** MB */
    private Long memoryTotal;

    /** MB */
    private Long diskTotal;

    private Boolean enabled;
}
