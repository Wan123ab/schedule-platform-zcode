package com.flowops.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 算子版本的组成部件（上传入参与详情出参同形，故共用一组类型）。
 *
 * <p><b>为什么这里没有 jakarta 校验注解</b>：docs/07 §6.3 要求上传失败返回
 * <b>42210 + errors[]</b>（逐字段回填，原型里有校验失败演示态），而注解式校验失败会走
 * 40001。两套错误码并存会让前端为同一个表单写两种错误渲染。故校验统一由
 * {@code OperatorVersionValidator} 收集成 {@code List<FieldError>}，一次性返回 ——
 * 也顺带解决了"注解式校验只报第一个错、用户要反复提交"的体验问题。</p>
 */
public class OperatorVersionParts {

    private OperatorVersionParts() {
    }

    /** 环境变量项（docs/05 §3.3：{@code [{key,value,secret}]}）。 */
    @Data
    public static class EnvVar {
        private String key;
        private String value;
        /** secret=true 的项在日志、快照、试运行回显中一律打码（PRD §13.3-2） */
        private Boolean secret;
    }

    /** 默认资源（继承链第 4 层，PRD §12.5）。 */
    @Data
    public static class DefaultResource {
        private BigDecimal cpu;
        private BigDecimal gpu;
        /** 内存（MB） */
        private Long memory;
        /** 磁盘（MB） */
        private Long disk;
    }

    /** 参数定义（docs/05 §3.3 operator_param_def）。 */
    @Data
    public static class ParamDef {
        private String name;
        /** 变量引用用的 key（{@code ${step.x.params.key}} 里的 key） */
        private String paramKey;
        /** TEXT / NUMBER / BOOLEAN / SINGLE / DATETIME */
        private String paramType;
        private Boolean required;
        private String defaultValue;
        private String rule;
        private String help;
        private Boolean runtimeOverridable;
        private Boolean sensitive;
        /** SINGLE 类型的候选值 */
        private List<String> options;
        private Integer seq;
    }

    /** 输出声明（docs/05 §3.3 operator_output_decl）。 */
    @Data
    public static class OutputDecl {
        /** 变量名：{@code ${step.X.output.varName}} 的取值键 */
        private String varName;
        /** REGEX / FILE */
        private String extractMode;
        /** 正则表达式 或 文件路径模板 */
        private String expression;
        private String valueType;
        private String exampleValue;
        private String description;
        private Boolean required;
        private Integer seq;
    }
}
