package com.flowops.modules.auth.scope;

import com.flowops.common.context.ScopeContext;
import com.flowops.common.context.UserContext;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
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
    void AUTHORIZED_CLUSTER范围_可见集群集来自授权查询_M2新增() {
        when(queries.roleScopeTypes(7L)).thenReturn(Set.of("AUTHORIZED_CLUSTER"));
        when(queries.authorizedClusterIds(7L)).thenReturn(Set.of(11L, 12L));

        ScopeContext scope = resolver.resolve(user());

        assertThat(scope.getType()).isEqualTo(ScopeContext.Type.AUTHORIZED_CLUSTER);
        assertThat(scope.getVisibleClusterIds()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(scope.getVisibleProjectIds()).isEmpty();   // 运维范围不按项目收窄
    }

    @Test
    void 项目管理员兼运维_两个可见集都备好_类型取更宽者() {
        when(queries.roleScopeTypes(7L)).thenReturn(Set.of("PROJECT", "AUTHORIZED_CLUSTER"));
        when(queries.memberProjectIds(7L)).thenReturn(Set.of(1L));
        when(queries.authorizedClusterIds(7L)).thenReturn(Set.of(11L));

        ScopeContext scope = resolver.resolve(user());

        assertThat(scope.getType()).isEqualTo(ScopeContext.Type.AUTHORIZED_CLUSTER);
        // 行级过滤按表列的维度二选一，故两个集合都必须备好，缺一个就会静默少算
        assertThat(scope.getVisibleProjectIds()).containsExactly(1L);
        assertThat(scope.getVisibleClusterIds()).containsExactly(11L);
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
