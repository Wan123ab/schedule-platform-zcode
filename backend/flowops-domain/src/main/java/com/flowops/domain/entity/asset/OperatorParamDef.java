package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 算子版本参数定义（docs/05 §3.3 operator_param_def，R9）—— 随版本快照，发布后禁改。
 *
 * <p><b>为什么随版本快照而不是挂在算子下</b>：参数模板是"这一版代码怎么被调用"的契约。
 * 若挂在算子维度，改一次参数就会让所有历史版本的含义一起变 —— 于是历史任务重跑时
 * 会拿到与当初不同的参数，问题排查彻底失去参照。</p>
 *
 * <p><b>没有 version / deleted 列</b>：本表是版本的从属快照，随版本一起被替换/删除
 * （DDL 里 {@code ON DELETE CASCADE}），不做单行软删与乐观锁 —— 实体因此刻意不带这两个字段。</p>
 */
@Data
@TableName(value = "operator_param_def", autoResultMap = true)
public class OperatorParamDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属版本内部主键（CASCADE：版本删则参数随之删） */
    private Long operatorVersionId;

    /** 展示顺序 */
    private Integer seq;

    /** 展示名（人看的） */
    private String name;

    /** 变量引用用的 key（${step.x.params.paramKey} 里的 paramKey） */
    private String paramKey;

    /** TEXT / NUMBER / BOOLEAN / SINGLE / DATETIME */
    private String paramType;

    private Boolean required;

    private String defaultValue;

    /** 校验规则（自由文本；SINGLE 类型用 options 表达候选） */
    private String rule;

    private String help;

    /** 允许运行时覆盖（false = 步骤级不可改，只能吃默认值） */
    private Boolean runtimeOverridable;

    /** 敏感参数：全链路脱敏（PRD §13.3-2），日志/快照/试运行回显都要打码 */
    private Boolean sensitive;

    /** SINGLE 类型的候选值 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String options;

    private OffsetDateTime createdAt;
}
