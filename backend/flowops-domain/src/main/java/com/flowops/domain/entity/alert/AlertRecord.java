package com.flowops.domain.entity.alert;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import com.flowops.domain.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 告警记录（docs/05 §3.7 alert_record，DDL 唯一真源）。
 *
 * <p><b>R20 多态引用</b>：target_type + target_id 指向任务/步骤/节点/队列/凭据/触发器，
 * 无外键。</p>
 */
@Data
@TableName(value = "alert_record", autoResultMap = true)
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 AL-####（docs/05 §6.2 编号清单未定义，同族风格自定 —— README-M4 O-63） */
    private String alertId;

    /** 规则行 id（规则删除后 SET NULL，记录保留） */
    private Long ruleId;

    private String eventType;

    /** CRITICAL / WARN / INFO */
    private String level;

    /** TASK / TASK_STEP / EXECUTOR_NODE / QUEUE / CREDENTIAL / TRIGGER */
    private String targetType;

    private Long targetId;

    private String targetName;

    private Long projectId;

    private Long clusterId;

    private String content;

    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] channels;

    /** 逐渠道送达结果 [{channel,ok,error,latencyMs}] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String deliverResult;

    /** SENT / PARTIAL / FAILED / SUPPRESSED */
    private String result;

    private OffsetDateTime firedAt;

    private OffsetDateTime createdAt;
}
