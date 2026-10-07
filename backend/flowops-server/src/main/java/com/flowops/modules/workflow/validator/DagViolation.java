package com.flowops.modules.workflow.validator;

/**
 * DAG 校验的一处违规（docs/07 §9.2 的 {@code errors[]} 元素）。
 *
 * <p><b>为什么不是"抛第一个错"</b>：用户面对的是一个画布，改一处就要重新保存、
 * 再被下一个错误打回。一次收齐、逐条定位（{@code step_name} + {@code rule}）
 * 才能让"改一轮就过"。这与算子上传校验失败（42210）的处理口径一致。</p>
 *
 * @param rule      规则编号（docs/07 §9.2 的表号：1~10）。前后端共用该编号，
 *                  前端据此把错误挂到对应节点上，而不是靠解析中文
 * @param stepName  出错的步骤名；工作流级错误（如规则 8）为 {@code null}
 * @param message   给人看的提示（模板见 docs/07 §9.2）
 * @param errorCode 该条违规应回的错误码：多数为 {@code 42213}，
 *                  变量引用类为 {@code 42214}，引用未发布算子为 {@code 42218}
 */
public record DagViolation(String rule, String stepName, String message, String errorCode) {

    /** 码：DAG 结构/规则类（docs/07 §4.2 的 42213）。 */
    public static final String CODE_DAG = "42213";
    /** 码：变量引用无效（42214）。 */
    public static final String CODE_VARIABLE = "42214";
    /** 码：引用了未发布的算子版本（42218）。 */
    public static final String CODE_UNPUBLISHED_OPERATOR = "42218";

    public static DagViolation of(String rule, String stepName, String message) {
        return new DagViolation(rule, stepName, message, CODE_DAG);
    }

    public static DagViolation variable(String rule, String stepName, String message) {
        return new DagViolation(rule, stepName, message, CODE_VARIABLE);
    }

    public static DagViolation unpublishedOperator(String stepName, String message) {
        return new DagViolation("7", stepName, message, CODE_UNPUBLISHED_OPERATOR);
    }
}
