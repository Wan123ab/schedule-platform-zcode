package com.flowops.common.exception;

import com.flowops.common.api.ApiResult;
import com.flowops.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * 全局异常处理（docs/07 §4.3）：
 * 校验失败 → 40001 + errors[]；业务异常 → 错误码；系统异常 → 50000 + traceId。
 * HTTP 状态码跟随错误码语义（线协议 HTTP 层与 body.code 一致）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record FieldErrorItem(String field, String message) {}

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResult<Map<String, Object>>> handleValidation(BindException e) {
        List<FieldErrorItem> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::toItem)
                .toList();
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResult.failWith(ErrorCode.PARAM_INVALID, Map.of("errors", errors)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleConstraint(ConstraintViolationException e) {
        List<FieldErrorItem> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldErrorItem(lastPath(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResult.failWith(ErrorCode.PARAM_INVALID, Map.of("errors", errors)));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResult<Void>> handleParamFormat(Exception e) {
        log.warn("参数格式错误: {}", e.getMessage());
        return buildVoid(ErrorCode.PARAM_FORMAT);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Object>> handleBiz(BizException e) {
        log.info("业务异常 code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(ApiResult.of(e.getErrorCode().getCode(), e.getMessage(), e.getPayload()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResult.failWith(ErrorCode.NOT_FOUND,
                        Map.of("resource_type", "path", "resource_id", String.valueOf(e.getResourcePath()))));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        return buildVoid(ErrorCode.INTERNAL_ERROR);
    }

    private FieldErrorItem toItem(FieldError fe) {
        return new FieldErrorItem(fe.getField(), fe.getDefaultMessage());
    }

    private String lastPath(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private <T> ResponseEntity<ApiResult<T>> buildVoid(ErrorCode ec) {
        return ResponseEntity.status(ec.httpStatus()).body(ApiResult.fail(ec));
    }
}
