package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DAG 里的一个步骤节点（CONTRACT §6.2/§6.7；DDL {@code workflow_step}）。
 *
 * <p><b>{@code stepId} 在本请求里是"本次保存会话内的稳定键"</b>，不是数据库主键：</p>
 * <ul>
 *   <li>前端新建节点时自己起一个（原型用 {@code s1}/{@code s2}），
 *       {@link DagEdgeDef} 的 {@code sourceStepId}/{@code targetStepId} 就是引它；</li>
 *   <li>服务端保存时<b>重新发号</b>（{@code WFS-<工作流数字段>-<版本序号>-<步骤序号>}）
 *       并回填到响应的同名字段 —— 因为 {@code uk_wstep_step_id} 是<b>全表</b>唯一索引，
 *       十几个工作流都用 {@code s1} 会当场撞索引；</li>
 *   <li>所以这个字段在"请求"与"响应"里的取值可以不同，语义是<b>同一次保存内的锚点</b>。
 *       回读一次（GET 版本）之后再保存，锚点会自动变成服务端发的号，行为一致。</li>
 * </ul>
 *
 * <p><b>交叉引用一律出业务编号</b>（D-27）：{@code operatorId}=OP-xxxx、
 * {@code operatorVersionId}=OPV-xxxx-xx、{@code targetClusterId}=CL-xxxx、
 * {@code targetQueueId}=QU-xxxx。服务端解析为内部主键后落库。</p>
 *
 * <p><b>这里只放"填错就该 40001"的注解校验</b>（缺名字、类型不在枚举）。图的业务规则
 * （环、可达性、必填参数、变量引用…）一律走 {@code DagValidator} → <b>42213/42214/42218</b>，
 * 因为那些错误要按节点逐条挂到画布上，而注解校验只会抛第一个错。</p>
 */
@Data
public class DagStepDef {

    /** 本次保存会话内的稳定键，同一请求内唯一（长度对齐 varchar(32) 的 step_id 列） */
    @NotBlank(message = "步骤缺少内部键（stepId）")
    @Size(max = 32, message = "步骤内部键最长 32 字符")
    private String stepId;

    /** 步骤名 = 变量引用键（D-20：{@code ${step.<步骤名>.output.<变量名>}}），工作流内唯一（规则 10） */
    @NotBlank(message = "步骤名称必填")
    @Size(max = 128, message = "步骤名称最长 128 字符")
    private String stepName;

    /** TASK（执行步骤）/ NOTE（备注节点；不参与可达性、算子绑定与资源校验） */
    @NotBlank(message = "步骤类型必填")
    @Pattern(regexp = "TASK|NOTE", message = "步骤类型取值非法")
    private String stepType;

    private String description;

    /** 算子业务编号 OP-xxxx（规则 2：TASK 必填） */
    private String operatorId;

    /** 算子版本业务编号 OPV-xxxx-xx（规则 2；规则 7 要求其 publish_status=PUBLISHED） */
    private String operatorVersionId;

    /** 算子参数（值可含变量引用，规则 3/4 的检查对象） */
    private Map<String, Object> params;

    /** 自定义参数（不参与算子必填校验，但同样会做变量引用检查） */
    private Map<String, Object> customParams;

    /** 目标集群业务编号 CL-xxxx（规则 6 按它聚合资源） */
    private String targetClusterId;

    /** 目标队列业务编号 QU-xxxx */
    private String targetQueueId;

    /** LINUX / WINDOWS（对齐 DDL 的 CHECK） */
    @Pattern(regexp = "LINUX|WINDOWS", message = "操作系统约束取值非法")
    private String osConstraint;

    /** 执行节点标签约束（PRD §10.8 第 8 项，如必须为 gpu 节点） */
    private List<String> tagConstraint;

    private BigDecimal cpu;

    private BigDecimal gpu;

    /** 内存（MB） */
    private Long memory;

    /** 磁盘（MB） */
    private Long disk;

    private Integer timeoutSeconds;

    /** 重试次数（规则 9：≤ 10） */
    private Integer retryCount;

    private Integer retryIntervalSeconds;

    /** TERMINATE / RETRY（对齐 DDL 的 CHECK；PRD 明确一期不支持 IGNORE） */
    @Pattern(regexp = "TERMINATE|RETRY", message = "失败策略取值非法")
    private String failureStrategy;

    /** 互斥锁组名（PRD §12.3；同组步骤不并发） */
    @Size(max = 128, message = "互斥锁名称最长 128 字符")
    private String mutexGroup;

    /** 画布坐标（PRD §10.8 能力 7：支持移动步骤位置） */
    private BigDecimal posX;

    private BigDecimal posY;
}
