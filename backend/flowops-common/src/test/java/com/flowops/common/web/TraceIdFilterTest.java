package com.flowops.common.web;

import com.flowops.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceIdFilter（O-19）。
 *
 * <p>它一共只有三件事，但每一件写错都<b>不抛异常、也不体现在任何返回值上</b>：
 * ① 客户端传来的合规 traceId 要沿用（否则跨系统调用链断在边界上）；
 * ② 不合规的要忽略并重新生成（否则一个随便写的头就能污染日志系统）；
 * ③ 过滤器结束时必须把 MDC 与 ThreadLocal 都清掉 —— 虚拟线程会被复用，
 *    漏清的后果是"下一条请求沿用了上一条的 traceId"，查日志时整条链是假的。</p>
 *
 * <p>用 {@code MockHttpServletRequest} + {@code MockFilterChain} 而不是起容器：
 * 过滤器要验的就是"请求头 → 响应头 → MDC"这一段，跟 Servlet 容器无关。</p>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        MDC.remove("traceId");
    }

    /**
     * MDC 只在过滤器<b>执行期间</b>有值 —— filter 的 finally 会把它清掉（这是对的，
     * 否则线程复用会串号）。所以要断言"MDC 被正确写入"，必须在链内取样，
     * 而不是等过滤器返回后再看（那时已经被清理干净了）。
     */
    private String[] captureDuringFilter() throws Exception {
        String[] seen = new String[2];
        jakarta.servlet.Servlet sampler = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                  jakarta.servlet.http.HttpServletResponse resp) {
                seen[0] = TraceContext.getTraceId();
                seen[1] = MDC.get("traceId");
            }
        };
        filter.doFilter(request, response, new MockFilterChain(sampler));
        return seen;
    }

    @Test
    void 无传入时生成32位traceId并回写响应头() throws Exception {
        filter.doFilter(request, response, chain);

        String echoed = response.getHeader(TraceIdFilter.HEADER);
        assertThat(echoed).matches("[0-9a-f]{32}");
    }

    @Test
    void 过滤期间MDC与TraceContext都被写入同一个traceId() throws Exception {
        String[] seen = captureDuringFilter();

        assertThat(seen[0]).matches("[0-9a-f]{32}");
        assertThat(seen[1]).isEqualTo(seen[0]);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seen[0]);
    }

    @Test
    void 合规入参被沿用_不重新生成() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "AABBCCDD11223344AABBCCDD11223344");

        filter.doFilter(request, response, chain);

        // 大写要归一成小写：同一个 traceId 在两套大小写下会被日志系统当成两条链
        assertThat(response.getHeader(TraceIdFilter.HEADER))
                .isEqualTo("aabbccdd11223344aabbccdd11223344");
    }

    @Test
    void 带连字符的UUID形态被归一无连字符() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "1e059566-223d-4ce1-8b34-3b2f1a6c236d");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.HEADER))
                .isEqualTo("1e059566223d4ce18b343b2f1a6c236d");
    }

    @Test
    void 不合规入参被忽略并重新生成() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "not-a-trace-id");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    void 长度不足32位也要重新生成() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "abcd");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{32}");
    }

    /**
     * 最要紧的一条：过滤器结束必须把 MDC 与 ThreadLocal 都清干净。
     *
     * <p>Java 侧走虚拟线程，线程会被复用；漏清时"下一条请求沿用了上一条的 traceId"，
     * 表现为日志里两条不相干的请求串成一条链 —— 排查时会被彻底误导，
     * 而且它不会报错，只能靠这里的断言兜住。</p>
     */
    @Test
    void 结束后清理ThreadLocal与MDC() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "aabbccdd11223344aabbccdd11223344");

        filter.doFilter(request, response, chain);

        assertThat(TraceContext.getTraceId()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void 下游异常时同样完成清理() throws Exception {
        MockFilterChain boom = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                throw new java.io.IOException("下游炸了");
            }
        };

        try {
            filter.doFilter(request, response, boom);
        } catch (java.io.IOException expected) {
            // 过滤器不该吞异常：它只负责上下文，不负责错误处理
        }

        assertThat(TraceContext.getTraceId()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }
}
