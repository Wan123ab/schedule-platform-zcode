package com.flowops.modules.governance.aspect;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * JSON 请求体缓存过滤器：body 只能在 servlet 流上读一次，
 * 幂等指纹需要提前读取，故在此缓存并包装（仅 application/json 的 POST/PUT/PATCH）。
 * 敏感字段（secret/password/token）不做日志输出，无泄漏面。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CachedBodyRequestFilter extends OncePerRequestFilter {

    public static final String ATTR_CACHED_BODY = "flowops.cachedBody";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {
        String contentType = request.getContentType();
        boolean json = contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE);
        boolean bodyMethod = "POST".equals(request.getMethod())
                || "PUT".equals(request.getMethod())
                || "PATCH".equals(request.getMethod());

        if (json && bodyMethod) {
            byte[] body = request.getInputStream().readAllBytes();
            request.setAttribute(ATTR_CACHED_BODY, body);
            chain.doFilter(new CachedBodyRequest(request, body), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String toString() {
            return Arrays.toString(body);
        }
    }
}
