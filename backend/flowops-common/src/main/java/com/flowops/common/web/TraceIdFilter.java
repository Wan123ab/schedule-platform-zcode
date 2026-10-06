package com.flowops.common.web;

import com.flowops.common.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceFilter（docs/07 §7.1）：
 * 客户端可传 X-Trace-Id（格式合规则沿用，不合规忽略）；否则生成 32 位无连字符 hex。
 * 写入 MDC 供日志 pattern 使用，并在响应头回写。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String traceId = normalize(request.getHeader(HEADER));
            if (traceId == null) {
                traceId = TraceContext.generate();
            }
            TraceContext.setTraceId(traceId);
            MDC.put("traceId", traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            MDC.remove("traceId");
        }
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.replace("-", "").toLowerCase();
        return hex.matches("[0-9a-f]{32}") ? hex : null;
    }
}
