package com.flowops.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 异常 → 线协议错误码（O-81）。
 *
 * <p><b>为什么会漏</b>：登录校验是 Sa-Token 的 {@code SaInterceptor} 做的，
 * 它抛的是 {@link NotLoginException}（Sa-Token 自家异常）。
 * 而 {@code GlobalExceptionHandler} 在 {@code flowops-common}，只认识
 * {@code BizException} 与 Spring 的绑定异常 —— common **不依赖 Sa-Token**
 * （它还要被 scheduler 用，不该被会话框架污染），所以不可能在那里 catch。
 * 结果：未登录/token 过期的请求一路掉进"兜底 Exception"分支，返回 <b>50000</b>。</p>
 *
 * <p><b>后果比看起来严重</b>：前端对 40100 的处理是"清会话、跳登录页"，
 * 而 50000 的语义是"服务端坏了"。于是 token 过期后用户看到的是
 * "系统内部错误"而不是重新登录 —— 他既不知道要重登，也无法自行恢复
 * （刷新、重试都还是 500）。同时每一条都在日志里留下 ERROR 级堆栈，
 * 把真正的系统故障淹没掉。</p>
 *
 * <p><b>为什么放在 server 而不是 common</b>：这是 Sa-Token 与线协议之间的
 * 适配器，只存在于"用 Sa-Token 的那一侧"（server）。common 保持与会话框架无关。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)   // 必须优先于 common 的 GlobalExceptionHandler
@RestControllerAdvice
public class SaTokenExceptionAdvice {

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResult<Void>> handleNotLogin(NotLoginException e) {
        log.info("未登录访问: type={} message={}", e.getType(), e.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.httpStatus())
                .body(ApiResult.fail(ErrorCode.UNAUTHORIZED, "未登录或登录态已失效，请重新登录"));
    }

    /**
     * Sa-Token 的其余异常（如 {@code NotPermissionException}）兜底为 40300。
     *
     * <p>说明：本项目的权限点判定走自研的 {@code @RequiresPermission} 切面
     * （抛 {@code BizException(FORBIDDEN)}，由 common 的 handler 接管），
     * 这里只是防止将来有人用了 Sa-Token 原生注解时又掉回 50000。</p>
     */
    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<ApiResult<Void>> handleSaToken(SaTokenException e) {
        log.warn("Sa-Token 异常: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(ApiResult.fail(ErrorCode.FORBIDDEN, e.getMessage()));
    }
}
