package com.flowops.domain.entity.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import com.flowops.domain.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 工作流步骤（docs/05 §3.4 workflow_step）—— 版本快照的一部分，随版本冻结。
 *
 * <p><b>没有 {@code version} / {@code deleted}</b>：它不是独立生命周期实体，
 * 而是版本的从属快照。保存草稿时整包替换（先删后插，见
 * {@code WorkflowVersionMapper/WorkflowStepMapper}），单行没有"并发修改"的概念 ——
 * 给从属行加乐观锁只会制造无人会处理的 CAS 失败。</p>
 *
 * <p><b>步骤名是变量引用的键</b>：D-20 的语法是
 * {@code ${step.{步骤名}.output.{变量名}}}，故步骤名在工作流内必须唯一
 * （DAG 规则 10，靠 {@code uk_wstep_version_name}）。若允许重名，
 * 变量解析会出现二义 —— 这类问题在编辑期无人能看出来，只会在运行期给错值。</p>
 *
 * <p><b>资源与超时多为可空</b>：空表示"继承"（算子默认值 → 工作流默认值 → 平台默认），
 * 继承链见 PRD §12.5 / docs/03 §4.4。故这里<b>不</b>在实体层填默认值，
 * 填了就等于把"没配"和"配成默认值"混成同一件事。</p>
 */
@Data
@TableName(value = "workflow_step", autoResultMap = true)
public class WorkflowStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 步骤业务编号（画布与变量解析的稳定标识） */
    private String stepId;

    /** 所属版本内部主键 */
    private Long workflowVersionId;

    private String stepName;

    /** TASK（执行步骤）/ NOTE（备注节点，不参与可达性与必填参数校验） */
    private String stepType;

    private String description;

    // ── 算子绑定（DAG 规则 2：TASK 步骤必须两个都非空）──
    private Long operatorId;
    private Long operatorVersionId;

    /** 步骤参数（覆盖链第 6 层：最高优先级） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String params;

    /** 自定义参数（算子参数模板之外的补充键值） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String customParams;

    // ── 目标与约束 ──
    private Long targetClusterId;
    private Long targetQueueId;

    /** LINUX / WINDOWS */
    private String osConstraint;

    /** 节点标签约束（text[]，DAG 规则 6 的资源校验不涉及标签） */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] tagConstraint;

    // ── 资源要求（DAG 规则 6：聚合后不得超目标集群/队列上限）──
    private BigDecimal cpu;
    private BigDecimal gpu;
    private Long memory;
    private Long disk;

    // ── 超时与重试（继承链第 5 层）──
    private Integer timeoutSeconds;
    private Integer retryCount;
    private Integer retryIntervalSeconds;

    /** TERMINATE / RETRY */
    private String failureStrategy;

    /** 互斥组（PRD §12.3 步骤级锁；互斥组内串行） */
    private String mutexGroup;

    // ── 画布位置（前端 SVG 画布直接读写）──
    private BigDecimal posX;
    private BigDecimal posY;

    private OffsetDateTime createdAt;
}
