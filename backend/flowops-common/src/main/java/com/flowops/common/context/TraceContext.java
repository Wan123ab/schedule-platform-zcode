package com.flowops.common.context;

/**
 * traceId 上下文（docs/07 §7.1）。
 * 一个 HTTP 请求 = 一个 trace_id，全链路复用；32 位无连字符小写 hex（对齐 W3C Trace Context）。
 */
public final class TraceContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {}

    public static String getTraceId() {
        return HOLDER.get();
    }

    public static void setTraceId(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 生成 32 位无连字符小写 hex。 */
    public static String generate() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
