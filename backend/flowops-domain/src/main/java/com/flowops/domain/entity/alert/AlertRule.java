package com.flowops.domain.entity.alert;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import com.flowops.domain.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 告警规则（docs/05 §3.7 alert_rule，DDL 唯一真源）。
 *
 * <p><b>event_type 取值</b>：以 DDL CHECK 的 9 值为准（TASK_FAILED / TASK_TIMEOUT /
 * STEP_RETRY_EXHAUSTED / NODE_OFFLINE / CONCURRENCY_LIMIT / QUEUE_BACKLOG /
 * RESOURCE_HIGH / CREDENTIAL_EXPIRED / TRIGGER_FAIL）—— CONTRACT §9 只列了 5 个，
 * 冲突已勘定以 DDL + V5 seed 为准（README-M4 §4 开头注记）。</p>
 *
 * <p><b>范围</b>：project_id / cluster_id 均可空 = 全局规则；非空 = 只对匹配 scope 的事件生效。</p>
 */
@Data
@TableName(value = "alert_rule", autoResultMap = true)
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 AR-#### */
    private String ruleId;

    private String ruleName;

    private String eventType;

    /** 按 event_type 定义的 JSON 条件（docs/05 §11：规则数量少，全表载入内存求值） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String condition;

    /** EMAIL / WECOM / WEBHOOK / DINGTALK */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] channels;

    /** 接收人（jsonb 数组；webhook 忽略，email 用） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String receivers;

    /** 抑制窗口（分钟）：同 event_type + target_id 窗口内只发一次（PRD §10.12） */
    private Integer suppressWindowMinutes;

    private Long projectId;

    private Long clusterId;

    private Boolean enabled;

    private String creator;

    private OffsetDateTime createdAt;

    private String createdBy;

    private OffsetDateTime updatedAt;

    private String updatedBy;

    @Version
    private Integer version;

    private Boolean deleted;
}
