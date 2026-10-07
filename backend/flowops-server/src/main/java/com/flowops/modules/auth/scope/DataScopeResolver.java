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
 * ③ AUTHORIZED_CLUSTER → 用户的"被授权集群"集（数据来源见 ClusterMapper 注释）。
 *
 * <p><b>为什么两个可见集都填、type 只取最宽的一个</b>：用户的多个角色可能分别落在
 * PROJECT 与 AUTHORIZED_CLUSTER（项目管理员 + 运维）。行级过滤按表列的维度二选一，
 * 所以两个集合都备好，由拦截器按表决定用哪个；type 表示"最宽范围"，用于需要单一
 * 语义的分支（如是否需要 SELF_CREATED 的 service 层细化）。</p>
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
        if (roleScopes.contains(ScopeType.PROJECT.getCode())) {
            scope.setVisibleProjectIds(scopeQueries.memberProjectIds(user.getUserId()));
        }
        if (roleScopes.contains(ScopeType.AUTHORIZED_CLUSTER.getCode())) {
            scope.setVisibleClusterIds(scopeQueries.authorizedClusterIds(user.getUserId()));
        }
        if (roleScopes.contains(ScopeType.ALL.getCode())) {
            scope.setType(ScopeContext.Type.ALL);
        } else if (roleScopes.contains(ScopeType.AUTHORIZED_CLUSTER.getCode())) {
            scope.setType(ScopeContext.Type.AUTHORIZED_CLUSTER);
        } else if (roleScopes.contains(ScopeType.PROJECT.getCode())) {
            scope.setType(ScopeContext.Type.PROJECT);
        } else {
            // SELF_CREATED 的行级过滤（submitter/creator = 本人）由各域 service 层注入（M2 后续）
            scope.setType(roleScopes.contains(ScopeType.SELF_CREATED.getCode())
                    ? ScopeContext.Type.SELF_CREATED : ScopeContext.Type.NONE);
        }
        return scope;
    }

    /** 角色范围查询的最小接口（便于单测隔离；实现见 MyBatisAuthScopeQueries）。 */
    public interface AuthScopeQueries {
        /** 角色数据范围类型集合（role.scope_type）。 */
        java.util.Set<String> roleScopeTypes(Long userId);

        /** PROJECT 范围的可见项目集（project_member）。 */
        java.util.Set<Long> memberProjectIds(Long userId);

        /** AUTHORIZED_CLUSTER 范围的可见集群集（授权口径见 ClusterMapper#findAuthorizedClusterIds）。 */
        java.util.Set<Long> authorizedClusterIds(Long userId);
    }
}
