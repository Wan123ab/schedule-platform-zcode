package com.flowops.modules.auth.scope;

import com.flowops.common.context.ScopeContext;
import com.flowops.common.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 数据范围解析器单测（docs/07 §5.3：多角色并集、宽者优先、凭据来自 DB 非前端）。 */
@ExtendWith(MockitoExtension.class)
class DataScopeResolverTest {

    @Mock
    private DataScopeResolver.AuthScopeQueries queries;

    private DataScopeResolver resolver;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        resolver = new DataScopeResolver(queries);   // mock 注入完成后构造（字段初始化会拿到 null）
    }

    private UserContext user() {
        UserContext ctx = new UserContext();
        ctx.setUserId(7L);
        return ctx;
    }

    @Test
    void 多角色并集_宽优先_ALL压制其余() {
        when(queries.roleScopeTypes(7L)).thenReturn(Set.of("PROJECT", "ALL"));

        assertThat(resolver.resolve(user()).getType()).isEqualTo(ScopeContext.Type.ALL);
    }

    @Test
    void PROJECT范围_可见项目集来自member表() {
        when(queries.roleScopeTypes(7L)).thenReturn(Set.of("PROJECT"));
        when(queries.memberProjectIds(7L)).thenReturn(Set.of(1L, 3L));

        ScopeContext scope = resolver.resolve(user());

        assertThat(scope.getType()).isEqualTo(ScopeContext.Type.PROJECT);
        assertThat(scope.getVisibleProjectIds()).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void 仅SELF_CREATED_无项目集注入() {
        when(queries.roleScopeTypes(7L)).thenReturn(Set.of("SELF_CREATED"));

        ScopeContext scope = resolver.resolve(user());

        assertThat(scope.getType()).isEqualTo(ScopeContext.Type.SELF_CREATED);
        assertThat(scope.getVisibleProjectIds()).isEmpty();   // 行级过滤由 service 层注入（M2 后续）
    }

    @Test
    void 无用户_返回NONE_拦截器不过滤() {
        assertThat(resolver.resolve(null).getType()).isEqualTo(ScopeContext.Type.NONE);
    }
}
