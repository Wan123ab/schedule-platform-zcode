package com.flowops.modules.governance.aspect;

import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 权限点校验切面（D-18 / D-19，docs/07 §5.2 §5.3）。
 * 权限点判「能不能做」；数据范围由 DataScopeInterceptor 在 Mapper 层注入（M2 落地）。
 * 49 点清单以 docs/07 §5.2 为唯一权威来源。
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Around("@annotation(requiresPermission)")
    public Object check(ProceedingJoinPoint pjp, RequiresPermission requiresPermission) throws Throwable {
        UserContext user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.hasPermission(requiresPermission.value())) {
            log.info("权限拒绝 user={} perm={}", user.getUsername(), requiresPermission.value());
            throw new BizException(ErrorCode.FORBIDDEN, "无权限：" + requiresPermission.value(),
                    Map.of("required", requiresPermission.value()));
        }
        return pjp.proceed();
    }
}
