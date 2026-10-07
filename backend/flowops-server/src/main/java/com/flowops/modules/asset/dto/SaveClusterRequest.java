package com.flowops.modules.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 集群创建/更新入参（docs/05 §3.3 cluster）。
 * 资源总量（cpu/gpu/memory/disk_total）是"集群标称容量"，用于展示与额度校验，
 * 与节点实测值汇总（后续 30s 聚合）分别维护。
 */
@Data
public class SaveClusterRequest {

    @NotBlank(message = "集群名称必填")
    private String clusterName;

    /** SPEC §6.2 集群类型；一期默认 GENERAL（不填即默认值） */
    @Pattern(regexp = "GENERAL|GPU|BIGDATA|CUSTOM", message = "集群类型不合法")
    private String clusterType;

    /** NORMAL / MAINTENANCE（其余两态由心跳链路自动推导，不接受人工设置） */
    @Pattern(regexp = "NORMAL|MAINTENANCE", message = "集群状态仅允许 NORMAL|MAINTENANCE")
    private String status;

    private BigDecimal cpuTotal;

    private BigDecimal gpuTotal;

    /** MB */
    private Long memoryTotal;

    /** MB */
    private Long diskTotal;
}
