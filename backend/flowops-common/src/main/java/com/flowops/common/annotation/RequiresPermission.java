package com.flowops.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验（D-18，docs/07 §5.2）。
 * 权限点命名 schedule:{域}:{动作}，49 点完整清单见 docs/07 §5.2；
 * 前端路由 meta.perm 与后端本注解都从该清单取值。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 权限点，如 schedule:task:stop。 */
    String value();

    /** 数据范围声明（D-19：与权限点正交）。默认 ALL 表示按角色收敛。 */
    String scope() default "ALL";
}
