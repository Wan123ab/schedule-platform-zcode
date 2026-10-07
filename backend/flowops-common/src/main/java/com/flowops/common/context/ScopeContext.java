package com.flowops.common.context;

import lombok.Data;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 数据范围上下文（D-19，docs/07 §5.3）—— 与 UserContext 正交：
 * 权限点判「能不能做」（UserContext），本上下文判「对哪些数据做」。
 *
 * <p>由 Web 层的 DataScopeResolver 解析后填充，MyBatis 的数据权限拦截器在查询期读取。
 * Scheduler 进程无此上下文（无 HTTP），拦截器对空上下文不过滤 —— 调度器必须看到全部数据。</p>
 *
 * <p><b>两个可见集分别对应两种行级过滤</b>：{@link #visibleProjectIds} 供 PROJECT 范围注入
 * {@code project_id}，{@link #visibleClusterIds} 供 AUTHORIZED_CLUSTER 范围注入 {@code cluster_id}。
 * 两者可同时存在（多角色并集），拦截器按「表上存在哪一列」决定用哪个。</p>
 */
@Data
public class ScopeContext {

    public enum Type { ALL, AUTHORIZED_CLUSTER, PROJECT, SELF_CREATED, NONE }

    private Type type = Type.NONE;

    /** PROJECT 范围下的可见项目 id 集（用户的 project_member 集合）。 */
    private Set<Long> visibleProjectIds = Set.of();

    /** AUTHORIZED_CLUSTER 范围下的可见集群 id 集（运维的"被授权集群"）。 */
    private Set<Long> visibleClusterIds = Set.of();

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

    /**
     * 在<b>临时关闭行级过滤</b>的临界区里执行查询，仅用于「存在性探测」。
     *
     * <p><b>为什么需要它</b>：越权语义要求区分 40400（不存在）与 40301（存在但越权）。
     * 但行级过滤会让「存在但越权」的行直接消失 → 两种情形都返回 null，无法区分。
     * 故这里开一个极短的旁路：临时置空上下文 → 探测该 id 是否存在 → 立即恢复原上下文。</p>
     *
     * <p><b>纪律（务必遵守）</b>：临界区内的返回值<b>不得</b>用于构造响应体，只允许取 boolean；
     * 探测完即丢弃，否则等于自行绕过了数据权限。此约束由调用点代码 Review 保证。</p>
     */
    public static <T> T withoutScope(Supplier<T> action) {
        ScopeContext previous = HOLDER.get();
        HOLDER.set(null);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
