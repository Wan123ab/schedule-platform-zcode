package com.flowops.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解式审计（docs/03 §3.3 / 07 §7.3）。
 * 必审动作清单见 docs/07 §7.3（55 个，M5 追加 4 个 OPENAPI 后为 59 个）。
 * M0 落地：环绕执行 + 异步写 audit_log（action/operator/trace_id/result）；
 * before/after 摘要与字段级 diff 在 M2 起按域补齐。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作码，如 STOP_TASK / PUBLISH_WORKFLOW。 */
    String action();

    /** 对象类型，对齐 audit_log.target_type CHECK（docs/05 §3.6）。 */
    String targetType();

    /** SpEL 表达式取目标业务编号，如 "#taskId"；空则不记 target_id。 */
    String targetIdExpr() default "";

    /** 摘要中需脱敏的字段名。 */
    String[] sensitiveFields() default {};
}
