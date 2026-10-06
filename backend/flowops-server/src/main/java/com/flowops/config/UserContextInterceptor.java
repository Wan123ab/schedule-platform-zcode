package com.flowops.config;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.flowops.common.context.ScopeContext;
import com.flowops.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * 当前用户上下文填充拦截器（docs/03 §3.1）。
 *
 * <p><b>设计思路</b>：登录时权限点列表一次性查库并写入 Sa-Token Session（见 AuthService），
 * 此后每个请求只做「会话 → ThreadLocal」的搬运；权限判定切面（PermissionAspect）只认
 * UserContext，不认请求参数 —— 身份来源唯一，杜绝伪造。</p>
 *
 * <p><b>为什么用 ThreadLocal 而不是把 UserContext 一路传参</b>：Java 侧虚拟线程按请求隔离，
 * ThreadLocal 即「本次请求」的作用域；afterCompletion 必须清理，防止线程复用串号。</p>
 */
public class UserContextInterceptor implements HandlerInterceptor {

    private final com.flowops.modules.auth.scope.DataScopeResolver dataScopeResolver;

    public UserContextInterceptor(com.flowops.modules.auth.scope.DataScopeResolver dataScopeResolver) {
        this.dataScopeResolver = dataScopeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StpUtil.isLogin()) {
            SaSession session = StpUtil.getSession();
            UserContext ctx = new UserContext();
            ctx.setUserId(StpUtil.getLoginIdAsLong());
            ctx.setUsername((String) session.get("username"));
            ctx.setDisplayName((String) session.get("displayName"));

            // Session 里存的是 List<String>；此处收成不可变 Set，让 hasPermission 是 O(1)
            Object perms = session.get("permissions");
            Set<String> permSet = perms instanceof List
                    ? Set.copyOf((List<String>) perms)
                    : Set.of();
            ctx.setPermissions(permSet);

            UserContext.set(ctx);
            // D-19：数据范围解析（角色 scope_type → 并集宽优先 → 可见项目集）
            ScopeContext.set(dataScopeResolver.resolve(ctx));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
