package com.flowops.common.exception;

import com.flowops.common.api.ApiResult;
import com.flowops.common.api.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常 → 线协议的映射（O-19，docs/07 §4.3）。
 *
 * <p><b>为什么值得测</b>：这个类是"任何一处出错最终都会流经的地方"。
 * 它判错一件事的代价不是"某功能坏了"，而是<b>所有错误都变成同一种样子</b> ——
 * 参数问题报成 50000，调用方就会去重试/报障；越权报成 40400，运维就会以为数据丢了。
 * 本轮 M5 的 O-78 / O-81 正是两个现成的例子：两者此前都掉进兜底分支成了 50000。</p>
 *
 * <p><b>为什么直接单测 handler 方法而不起 MockMvc</b>：要验的是"异常类型 → 错误码"的
 * 映射表本身，与 Servlet 容器、参数绑定过程无关。起容器反而会把"哪个 handler 被选中"
 * 这个 Spring 的行为混进来，掩盖映射表自身的错误。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 业务异常_按错误码原样回带_HTTP状态与body一致() {
        BizException e = new BizException(ErrorCode.SCOPE_EXCEEDED, "存在但越权",
                Map.of("resource_type", "TASK"));

        ResponseEntity<ApiResult<Object>> resp = handler.handleBiz(e);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40301);
        assertThat(resp.getBody().getMessage()).isEqualTo("存在但越权");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) resp.getBody().getData();
        assertThat(payload).containsEntry("resource_type", "TASK");
    }

    @Test
    void 参数校验失败_40001且带errors数组() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        // 用 addError 而不是 rejectValue：后者要去 target 对象上真实读属性值，
        // 这里 target 是个裸 Object，读不到属性会抛 NotReadablePropertyException
        binding.addError(new org.springframework.validation.FieldError("req", "workflowName", "工作流名称必填"));
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        when(e.getBindingResult()).thenReturn(binding);

        ResponseEntity<ApiResult<Map<String, Object>>> resp = handler.handleValidation(e);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40001);
        @SuppressWarnings("unchecked")
        List<GlobalExceptionHandler.FieldErrorItem> errors =
                (List<GlobalExceptionHandler.FieldErrorItem>) resp.getBody().getData().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).isEqualTo("工作流名称必填");
    }

    @Test
    void 约束违规_字段名取路径最后一段而不是整条路径() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("saveDraft.request.versionId");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("版本编号必填");

        ResponseEntity<ApiResult<Map<String, Object>>> resp =
                handler.handleConstraint(new jakarta.validation.ConstraintViolationException(Set.of(violation)));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40001);
        @SuppressWarnings("unchecked")
        List<GlobalExceptionHandler.FieldErrorItem> errors =
                (List<GlobalExceptionHandler.FieldErrorItem>) resp.getBody().getData().get("errors");
        // 前端按字段名回填表单，整条路径 "saveDraft.request.versionId" 是填不进输入框的
        assertThat(errors.get(0).field()).isEqualTo("versionId");
    }

    @Test
    void 参数类型不匹配_40002() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleParamFormat(
                new MethodArgumentTypeMismatchException("abc", Long.class, "pageSize", null, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40002);
    }

    @Test
    void 请求体不可解析_40001_而不是掉进兜底变50000() {
        // O-78：这个 handler 是 2026-10-25 加的。此前 HttpMessageNotReadableException
        // 会落到 handleUnexpected → 50000，于是"前端忘了传 body"被当成服务端故障
        ResponseEntity<ApiResult<Void>> resp =
                handler.handleUnreadableBody(new HttpMessageNotReadableException("Required request body is missing"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40001);
    }

    @Test
    void 路径不存在_40400且resource_type为path() {
        ResponseEntity<ApiResult<Map<String, Object>>> resp =
                handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/nope"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40400);
        assertThat(resp.getBody().getData()).containsEntry("resource_type", "path");
    }

    @Test
    void 兜底_系统异常_50000且不外泄堆栈() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleUnexpected(new IllegalStateException("DB 连接池耗尽"));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(50000);
        assertThat(resp.getBody().getMessage()).doesNotContain("DB 连接池耗尽");
    }

    @Test
    void BindException与MethodArgumentNotValid走同一个handler() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new org.springframework.validation.FieldError("req", "workflowName", "必填"));

        ResponseEntity<ApiResult<Map<String, Object>>> resp = handler.handleValidation(new BindException(binding));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(40001);
    }
}
