package com.flowops.common.exception;

import com.flowops.common.api.ErrorCode;
import lombok.Getter;

/** 业务异常：携带错误码，由 GlobalExceptionHandler 统一转换（docs/03 §3.5）。 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 附带数据：如 40901 的 running_task_id、42213 的 errors[]（序列化为线协议时随响应下发）。 */
    private final transient Object payload;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.payload = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.payload = null;
    }

    public BizException(ErrorCode errorCode, String message, Object payload) {
        super(message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }
}
