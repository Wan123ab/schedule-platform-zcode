package com.flowops.modules.governance.aspect;

import com.flowops.common.annotation.DataScope;
import com.flowops.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 数据范围切面骨架（D-19，docs/07 §5.3）。
 * M0：声明校验 + 上下文留痕；M2 落地 DataScopeInterceptor（Mapper 层 SQL 注入：
 *   ALL → 无条件；AUTHORIZED_CLUSTER → cluster_id IN (...)；
 *   PROJECT → project_id = 当前项目；SELF_CREATED → creator = 当前用户），
 * 并补 DataScope 越权自动化测试（40301，docs/09 M2 DoD）。
 */
@Slf4j
@Aspect
@Component
public class DataScopeAspect {

    @Around("@annotation(dataScope)")
    public Object apply(ProceedingJoinPoint pjp, DataScope dataScope) throws Throwable {
        UserContext user = UserContext.get();
        if (user != null && log.isDebugEnabled()) {
            log.debug("DataScope {} on {} (user={})", String.join(",", dataScope.value()),
                    pjp.getSignature().toShortString(), user.getUsername());
        }
        return pjp.proceed();
    }
}
