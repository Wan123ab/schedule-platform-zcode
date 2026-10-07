package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 算子版本输出声明（docs/05 §3.3 operator_output_decl，R10）—— 随版本快照，发布后禁改。
 *
 * <p><b>作用</b>：声明"这一步会产出哪些变量"，是 M3 变量解析链（docs/07 §9.3）中
 * {@code ${step.X.output.Y}} 能否被解析的依据；{@code required=true} 的输出未产出
 * 直接判步骤失败（PRD §10.0.2）。</p>
 *
 * <p><b>两种提取方式</b>：REGEX（从日志尾部匹配）/ FILE（读取文件内容），
 * 由 {@code extractMode} 区分、{@code expression} 承载具体表达式或路径模板。</p>
 */
@Data
@TableName("operator_output_decl")
public class OperatorOutputDecl {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属版本内部主键（CASCADE） */
    private Long operatorVersionId;

    private Integer seq;

    /** 变量名，必须匹配 [a-zA-Z_][a-zA-Z0-9_]*（否则变量引用无法解析） */
    private String varName;

    /** REGEX / FILE */
    private String extractMode;

    /** 正则表达式 或 文件路径模板 */
    private String expression;

    /** 值类型（TEXT / NUMBER / DATETIME 等，默认 TEXT） */
    private String valueType;

    private String exampleValue;

    private String description;

    /** 必填输出未产出 → 步骤失败（PRD §10.0.2） */
    private Boolean required;

    private OffsetDateTime createdAt;
}
