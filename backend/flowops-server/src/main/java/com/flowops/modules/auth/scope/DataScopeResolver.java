package com.flowops.modules.auth.scope;

import com.flowops.common.context.ScopeContext;
import com.flowops.common.context.UserContext;
import com.flowops.common.enums.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 数据范围解析器（D-19，docs/07 §5.3 判定链）：
 * ① 取角色 scope_type 集合 → 多角色取并集、宽者优先；
 * ② PROJECT → 用户的 project_member 项目集（授权凭据，非前端传入）；
 * ③ AUTHORIZED_CLUSTER → 集群级过滤在集群域查询注入（M2 后续），此处不限制项目。
 */
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final AuthScopeQueries scopeQueries;

    public ScopeContext resolve(UserContext user) {
        ScopeContext scope = new ScopeContext();
        if (user == null) {
            return scope;   // 无用户（登录接口等）：NONE，拦截器不过滤
        }
        var roleScopes = scopeQueries.roleScopeTypes(user.getUserId());
        if (roleScopes.contains(ScopeType.ALL.getCode())) {
            scope.setType(ScopeContext.Type.ALL);
            return scope;
        }
        if (roleScopes.contains(ScopeType.AUTHORIZED_CLUSTER.getCode())) {
            // 运维视图：按授权集群横向可见（集群维度过滤随集群域查询落地）
            scope.setType(ScopeContext.Type.AUTHORIZED_CLUSTER);
            return scope;
        }
        if (roleScopes.contains(ScopeType.PROJECT.getCode())) {
            scope.setType(ScopeContext.Type.PROJECT);
            scope.setVisibleProjectIds(scopeQueries.memberProjectIds(user.getUserId()));
            return scope;
        }
        // SELF_CREATED 的行级过滤（submitter/creator = 本人）由各域 service 层注入（M2 后续）
        scope.setType(roleScopes.contains(ScopeType.SELF_CREATED.getCode())
                ? ScopeContext.Type.SELF_CREATED : ScopeContext.Type.NONE);
        return scope;
    }

    /** 角色范围查询的最小接口（便于单测隔离；实现见 AuthQueryMapper/ProjectMemberMapper）。 */
    public interface AuthScopeQueries {
        java.util.Set<String> roleScopeTypes(Long userId);

        java.util.Set<Long> memberProjectIds(Long userId);
    }
}
