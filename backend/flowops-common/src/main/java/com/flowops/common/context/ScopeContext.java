package com.flowops.common.context;

import lombok.Data;

import java.util.Set;

/**
 * 数据范围上下文（D-19，docs/07 §5.3）—— 与 UserContext 正交：
 * 权限点判「能不能做」（UserContext），本上下文判「对哪些数据做」。
 *
 * <p>由 Web 层的 DataScopeResolver 解析后填充，MyBatis 的数据权限拦截器在查询期读取。
 * Scheduler 进程无此上下文（无 HTTP），拦截器对空上下文不过滤 —— 调度器必须看到全部数据。</p>
 */
@Data
public class ScopeContext {

    public enum Type { ALL, AUTHORIZED_CLUSTER, PROJECT, SELF_CREATED, NONE }

    private Type type = Type.NONE;

    /** PROJECT 范围下的可见项目 id 集（用户的 project_member 集合）。 */
    private Set<Long> visibleProjectIds = Set.of();

    private static final ThreadLocal<ScopeContext> HOLDER = new ThreadLocal<>();

    public static ScopeContext get() {
        return HOLDER.get();
    }

    public static void set(ScopeContext ctx) {
        HOLDER.set(ctx);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public boolean isAll() {
        return type == Type.ALL;
    }
}
