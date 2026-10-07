package com.flowops.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前用户上下文单测（权限点判"能不能做"，docs/07 §5.3）。
 *
 * <p>重点是 {@link UserContext#hasPermission} 的通配语义与默认值：默认必须"什么都不许"，
 * 若默认集合写成了 {@code Set.of("*")} 这类笔误，表现是所有接口对所有人开放，
 * 而请求链路没有任何异常可供发现。</p>
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 默认无用户且权限集为空() {
        assertThat(UserContext.get()).isNull();

        UserContext ctx = new UserContext();

        assertThat(ctx.getPermissions()).isEmpty();
        assertThat(ctx.getScopeTypes()).isEmpty();
        assertThat(ctx.hasPermission("schedule:operator:read")).isFalse();
    }

    @Test
    void 存取与清除() {
        UserContext ctx = new UserContext();
        ctx.setUserId(7L);
        ctx.setUsername("zhangsan");
        ctx.setPermissions(Set.of("schedule:operator:read"));

        UserContext.set(ctx);

        assertThat(UserContext.get().getUserId()).isEqualTo(7L);
        assertThat(UserContext.get().getUsername()).isEqualTo("zhangsan");

        UserContext.clear();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void 命中已授予权限点() {
        UserContext ctx = new UserContext();
        ctx.setPermissions(Set.of("schedule:operator:read", "schedule:operator:write"));

        assertThat(ctx.hasPermission("schedule:operator:read")).isTrue();
        assertThat(ctx.hasPermission("schedule:operator:delete")).isFalse();
    }

    /** 超级管理员用 "*" 表达，且只在显式授予时生效（不是默认值）。 */
    @Test
    void 通配权限点匹配任意权限() {
        UserContext ctx = new UserContext();
        ctx.setPermissions(Set.of("*"));

        assertThat(ctx.hasPermission("schedule:anything:at:all")).isTrue();
        assertThat(ctx.hasPermission("")).isTrue();
    }
}
