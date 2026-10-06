package com.flowops.common.context;

import lombok.Data;

/**
 * 当前用户上下文（ThreadLocal，由鉴权层填充）。
 * 权限点判「能不能做」，DataScope 判「对谁做」（D-19，docs/07 §5.3）。
 */
@Data
public class UserContext {

    private Long userId;
    private String username;
    private String displayName;
    private java.util.Set<String> permissions = java.util.Set.of();
    /** 角色默认数据范围；多角色取并集由 DataScopeInterceptor 收敛。 */
    private java.util.Set<String> scopeTypes = java.util.Set.of();

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void set(UserContext ctx) {
        HOLDER.set(ctx);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public boolean hasPermission(String perm) {
        return permissions.contains(perm) || permissions.contains("*");
    }
}
