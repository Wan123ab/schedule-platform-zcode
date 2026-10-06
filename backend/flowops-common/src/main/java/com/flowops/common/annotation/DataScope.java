package com.flowops.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据范围标注（D-19，docs/07 §5.3）。
 * M0 仅骨架：拦截器占位，M2 落地 DataScopeInterceptor 的 SQL 注入与越权断言。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /** 生效的数据范围，多角色取并集、宽者优先。 */
    String[] value() default {"ALL"};
}
