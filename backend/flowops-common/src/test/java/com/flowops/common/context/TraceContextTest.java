package com.flowops.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 上下文单测（docs/07 §7.1）。
 *
 * <p>格式必须严格是"32 位无连字符小写 hex"（对齐 W3C Trace Context）：
 * 带上 UUID 的连字符会让对端把 traceId 当成非法长度而丢弃，跨系统链路就此断开 ——
 * 而且断开是静默的，日志里看不出任何异常。</p>
 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void 默认无_traceId() {
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void 存取与清除() {
        TraceContext.setTraceId("0123456789abcdef0123456789abcdef");

        assertThat(TraceContext.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");

        TraceContext.clear();
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void 生成_32位无连字符小写hex() {
        String traceId = TraceContext.generate();

        assertThat(traceId).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(traceId).doesNotContain("-");
    }

    @Test
    void 连续生成不重复() {
        assertThat(TraceContext.generate()).isNotEqualTo(TraceContext.generate());
    }
}
