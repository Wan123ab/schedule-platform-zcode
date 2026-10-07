package com.flowops.domain.entity.workflow;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 触发器（docs/05 §3.4 {@code trigger}）—— 工作流的自动提交入口。
 *
 * <p><b>表名是 PG 保留字</b>：{@code trigger} 在 PostgreSQL 里是保留关键字，
 * 所有 SQL 必须写作 {@code "trigger"}（建表、索引、JOIN、UPDATE 全部如此；
 * 本类 {@code @TableName} 亦带引号）。docs/05 §3.4 的 DDL 原文没加引号，
 * 属于"从未在真实 PG 上跑过所以没炸"的潜伏缺陷，本轮修掉并登记（README-M3 §5-10）。</p>
 *
 * <p><b>可见性走父 workflow</b>（与 workflow_version 同一模式）：本表没有
 * {@code project_id} 列，行级数据权限<b>不</b>注册本表；Service 层先判父工作流
 * 的可见性，再以 {@code workflow_id} 精确取行。</p>
 *
 * <p><b>cron 与固定周期二选一</b>（DDL 注释）：{@code CRON} 类型至少给其一，
 * 两者都给拒绝（42216）；校验逻辑在 server 侧 TriggerConfigValidator（纯函数）。</p>
 */
@Data
@TableName(value = "\"trigger\"", autoResultMap = true)
public class Trigger {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 TRG-0001（docs/05 §6.2 的编号表漏了触发器，自定前缀并登记偏离） */
    private String triggerId;

    private String triggerName;

    /** 父工作流内部主键（出网翻译为 WF-xxxx，D-27） */
    private Long workflowId;

    /** MANUAL / CRON / API / EVENT（DDL CHECK；一期只放行前两种，API/EVENT 见 docs/07 §11） */
    private String triggerType;

    private String cronExpression;

    /** 固定周期秒数（与 cron_expression 二选一） */
    private Integer periodSeconds;

    /** 锁定版本（内部主键；null = 跟随最新发布版） */
    private Long lockedVersionId;

    private String timezone;

    /** 生效窗口，JSONB {@code {"start": "...", "end": "..."}}；以 String 承载 JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String effectiveRange;

    private Boolean enabled;

    /** 停用前的状态（项目停用联动恢复用，ProjectMapper.disableProjectTriggers） */
    private Boolean enabledBeforeDisable;

    /** 触发时附加参数（第 4 层"触发时参数"的来源），JSONB */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String runParams;

    private Long targetQueueId;

    /** 停机补跑（docs/06 §11.2） */
    private Boolean catchUpEnabled;
    private Integer catchUpMaxTimes;

    private OffsetDateTime nextFireTime;
    private OffsetDateTime lastFireTime;

    /** SUCCESS / FAILED / SKIPPED（调度器回写） */
    private String lastFireStatus;

    private Long lastFireTaskId;

    /** 告警配置，JSONB；一期只落库不消费 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String failNotify;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Integer version;

    private Boolean deleted;
}
