package com.flowops.modules.governance.aspect;

import com.flowops.common.annotation.Audited;
import com.flowops.common.context.TraceContext;
import com.flowops.common.context.UserContext;
import com.flowops.domain.entity.audit.AuditLog;
import com.flowops.domain.mapper.audit.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 注解式审计切面（docs/03 §3.3 / 07 §7.3）。
 * ① traceId 从 TraceContext 取（与日志、响应包同一标识）；
 * ② 异步落库（auditExecutor），失败不阻断业务但记录告警日志；
 * ③ 业务异常也记录（result=FAIL）后原样抛出；
 * ④ before/after 摘要与字段级 diff 自 M2 起按域补齐（M0 先落动作与上下文）。
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    private static final SpelExpressionParser SPEL = new SpelExpressionParser();

    private final AuditLogMapper auditLogMapper;
    private final Executor auditExecutor;

    public AuditAspect(AuditLogMapper auditLogMapper,
                       @Qualifier("auditExecutor") Executor auditExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.auditExecutor = auditExecutor;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        String result = "SUCCESS";
        String failReason = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            result = "FAIL";
            failReason = t.getMessage();
            throw t;
        } finally {
            try {
                write(audited, pjp, result, failReason);
            } catch (Exception e) {
                log.error("审计写入失败 action={}（审计缺失是合规风险，需告警）", audited.action(), e);
            }
        }
    }

    private void write(Audited audited, ProceedingJoinPoint pjp, String result, String failReason) {
        UserContext user = UserContext.get();

        AuditLog entry = new AuditLog();
        entry.setAuditId("AU-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entry.setOperatedAt(OffsetDateTime.now());
        entry.setOperator(user != null ? user.getUsername() : "anonymous");
        entry.setOperatorName(user != null ? user.getDisplayName() : null);
        entry.setTargetType(audited.targetType());
        entry.setTargetId(resolveTargetId(audited, pjp));
        entry.setAction(audited.action());
        entry.setResult(result);
        entry.setFailReason(failReason);
        entry.setTraceId(TraceContext.getTraceId());
        entry.setCreatedAt(OffsetDateTime.now());
        fillRequestMeta(entry);

        auditExecutor.execute(() -> {
            try {
                auditLogMapper.insert(entry);
            } catch (Exception e) {
                log.error("审计落库失败 audit_id={}", entry.getAuditId(), e);
            }
        });
    }

    private String resolveTargetId(Audited audited, ProceedingJoinPoint pjp) {
        String expr = audited.targetIdExpr();
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String[] names = signature.getParameterNames();
            Object[] args = pjp.getArgs();
            Map<String, Object> vars = new HashMap<>();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    vars.put(names[i], args[i]);
                }
            }
            StandardEvaluationContext ctx = new StandardEvaluationContext(vars);
            ctx.setVariables(vars);
            ctx.setRootObject(vars);
            Object value = SPEL.parseExpression(expr).getValue(ctx);
            return value != null ? String.valueOf(value) : null;
        } catch (Exception e) {
            log.warn("审计 targetIdExpr 求值失败 expr={}", expr, e);
            return null;
        }
    }

    private void fillRequestMeta(AuditLog entry) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            entry.setSourceIp(request.getRemoteAddr());
            entry.setUserAgent(request.getHeader("User-Agent"));
        }
    }
}
