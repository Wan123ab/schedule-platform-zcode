package com.flowops.common.api;

import com.flowops.common.context.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * 统一响应包（docs/07 §3.3，回写 F-01）。
 * 线协议经全局 SNAKE_CASE 策略输出：{code, message, data, trace_id}。
 * message 是面向开发者的摘要，非用户文案（用户文案由前端按 code 映射，docs/07 §4.3）。
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String traceId;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceContext.getTraceId();
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.OK.getCode(), "ok", data);
    }

    public static ApiResult<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(ErrorCode ec) {
        return new ApiResult<>(ec.getCode(), ec.getMessage(), null);
    }

    public static <T> ApiResult<T> fail(ErrorCode ec, String message) {
        return new ApiResult<>(ec.getCode(), message, null);
    }

    /** 失败但携带业务数据（如 40901 的 running_task_id、40001 的 errors[]）。 */
    public static <T> ApiResult<T> failWith(ErrorCode ec, T data) {
        return new ApiResult<>(ec.getCode(), ec.getMessage(), data);
    }

    public static <T> ApiResult<T> of(int code, String message, T data) {
        return new ApiResult<>(code, message, data);
    }
}
