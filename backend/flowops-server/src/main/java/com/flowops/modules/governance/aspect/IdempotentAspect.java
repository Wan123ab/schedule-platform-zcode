package com.flowops.modules.governance.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.annotation.Idempotent;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等切面（D-17，docs/07 §7.2）：
 * Redis key = idem:{user}:{key}，TTL 24h；命中同 key 同指纹 → 返回首次响应快照；
 * 命中同 key 不同指纹 → 40903。指纹 = method + path + body 的 SHA-256。
 * 口径「前端必带、后端不强拒」：仅 @Idempotent(required=true) 的端点（4 个高危端点）强制。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String HEADER = "Idempotency-Key";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            if (idempotent.required()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "Idempotency-Key 请求头必填");
            }
            return pjp.proceed();
        }

        String user = UserContext.get() != null ? String.valueOf(UserContext.get().getUserId()) : "anonymous";
        String redisKey = "idem:" + user + ":" + key;
        String fingerprint = sha256(request.getMethod() + " " + requestUri(request) + " " + cachedBody(request));

        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            String cachedFp = fingerprintOf(cached);
            if (!fingerprint.equals(cachedFp)) {
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            String snapshot = snapshotOf(cached);
            if (snapshot != null) {
                log.info("幂等命中 key={} 返回首次响应", key);
                return objectMapper.readValue(snapshot, Object.class);
            }
        }

        Object result = pjp.proceed();

        try {
            String record = fingerprint + "\n" + objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, record, Duration.ofSeconds(idempotent.ttlSeconds()));
        } catch (Exception e) {
            log.warn("幂等快照写入失败 key={}", key, e);
        }
        return result;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String requestUri(HttpServletRequest request) {
        Object best = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return best != null ? String.valueOf(best) : URI.create(request.getRequestURI()).getPath();
    }

    /** 依赖 CachedBodyRequestFilter 预读的请求体。 */
    private String cachedBody(HttpServletRequest request) {
        Object cached = request.getAttribute(CachedBodyRequestFilter.ATTR_CACHED_BODY);
        if (cached instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        Map<String, String[]> params = request.getParameterMap();
        return params.isEmpty() ? "" : new TreeMap<>(params).toString();
    }

    private String fingerprintOf(String record) {
        int idx = record.indexOf('\n');
        return idx > 0 ? record.substring(0, idx) : "";
    }

    private String snapshotOf(String record) {
        int idx = record.indexOf('\n');
        return idx > 0 && idx < record.length() - 1 ? record.substring(idx + 1) : null;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
