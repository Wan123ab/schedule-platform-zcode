package com.flowops.common.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码全量表（docs/07 §4.2，唯一权威来源）。
 * 五段式：{HTTP语义}0{域}{序号}；CONTRACT-API §12 已定义的 12 个码保持不变。
 */
@Getter
public enum ErrorCode {

    OK(0, 200, "成功"),

    // ── 通用（域 0）──
    PARAM_INVALID(40001, 400, "参数校验失败"),
    PARAM_FORMAT(40002, 400, "参数格式错误"),
    SORT_NOT_ALLOWED(40003, 400, "排序字段不在白名单"),
    UNAUTHORIZED(40100, 401, "未登录或 token 失效"),
    ACCOUNT_LOCKED(40101, 403, "账号已锁定"),
    TOKEN_REPLACED(40102, 401, "token 已在别处登录"),
    FORBIDDEN(40300, 403, "无权限"),
    SCOPE_EXCEEDED(40301, 403, "超出数据范围"),
    LOG_GRANT_REQUIRED(40302, 403, "需要日志查看授权"),
    NOT_FOUND(40400, 404, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, 405, "方法不允许"),
    STATUS_CONFLICT(40900, 409, "状态冲突"),
    RULE_NOT_SATISFIED(42200, 422, "业务规则不满足"),
    RATE_LIMITED(42900, 429, "触发限流"),

    // ── 资产域（域 1）──
    PROJECT_OWNER_UNREMOVABLE(42201, 422, "项目负责人不可移除"),
    CREDENTIAL_REFERENCED(42202, 422, "凭据被引用，禁止删除"),
    PROJECT_HAS_RUNNING_TASKS(42203, 422, "项目停用前存在运行中任务"),
    CLUSTER_HAS_NODES(42204, 422, "集群下仍有执行节点，禁止删除"),
    NODE_OCCUPIED(42205, 422, "节点被运行中任务占用，禁止禁用"),
    CREDENTIAL_EXPIRE_INVALID(42206, 422, "凭据过期时间早于当前时间"),
    QUOTA_EXCEEDS_CLUSTER(42207, 422, "项目额度超出集群上限"),

    // ── 编排域（域 2）──
    OPERATOR_UPLOAD_INVALID(42210, 422, "算子上传校验失败"),
    OPERATOR_VERSION_REFERENCED(42211, 422, "算子版本已被工作流引用，禁止删除"),
    VERSION_NOT_DRAFT(42212, 422, "非草稿版本不可编辑"),
    DAG_VALIDATE_FAILED(42213, 422, "工作流 DAG 校验失败"),
    VARIABLE_REF_INVALID(42214, 422, "变量引用无效"),
    DRAFT_CHANGES_PENDING(42215, 422, "工作流存在草稿变更，需先发布或丢弃"),
    CRON_INVALID(42216, 422, "Cron 表达式非法"),
    TRIGGER_WINDOW_INVALID(42217, 422, "触发器时间窗非法"),
    OPERATOR_VERSION_NOT_PUBLISHED(42218, 422, "引用了未发布的算子版本"),

    // ── 执行域（域 3）──
    BACKFILL_PREVIEW_INVALID(42231, 422, "回填预览已失效或未预览直提"),
    BACKFILL_RANGE_EXCEEDED(42232, 422, "回填范围超限"),
    TASK_NOT_STOPPABLE(42233, 422, "任务非可停止状态"),
    STOP_REASON_TOO_SHORT(42234, 422, "停止原因少于 5 字符"),
    TASK_NOT_FINISHED(42235, 422, "任务非终态，不可重跑"),
    RETRY_EXHAUSTED(42236, 422, "重试次数已达上限"),
    TASK_NOT_WAITING(42237, 422, "任务不在等待态，不可插队"),
    BACKFILL_STATUS_FORBIDDEN(42238, 422, "回填批次状态不允许该操作"),
    STEP_STOP_UNSUPPORTED(42239, 422, "步骤级停止一期不提供"),
    CONCURRENCY_FORBID(40901, 409, "并发策略 FORBID，工作流已在运行"),
    QUEUE_WAITING_FULL(40902, 409, "等待数已达上限"),
    IDEMPOTENCY_CONFLICT(40903, 409, "幂等键命中不同请求体"),
    MUTEX_GROUP_OCCUPIED(40904, 409, "互斥锁组被占用"),

    // ── 开放接口（v0.2d）──
    API_KEY_INVALID(40110, 401, "API Key 无效或已停用/过期"),
    API_KEY_SCOPE_EXCEEDED(40111, 403, "超出 Key 作用域"),
    API_KEY_IP_DENIED(40112, 403, "来源 IP 不在白名单"),
    API_RATE_LIMITED(42901, 429, "API 调用限流触发"),

    // ── 治理域（域 4）──
    ALERT_CONDITION_INVALID(42241, 422, "告警规则条件 JSON 不合法"),
    ALERT_CHANNEL_UNVERIFIED(42242, 422, "告警渠道未验证"),
    AUDIT_RETENTION_EXCEEDED(42243, 422, "审计日志保留期外不可查"),

    // ── 服务端 ──
    INTERNAL_ERROR(50000, 500, "服务内部错误"),
    DB_ERROR(50001, 500, "数据库异常"),
    DEPENDENCY_UNAVAILABLE(50300, 503, "依赖服务不可用"),
    SCHEDULER_NO_LEADER(50301, 503, "调度器未选出主"),
    NODE_CONNECT_FAILED(50302, 503, "执行节点连接失败");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return HttpStatus.valueOf(httpStatus);
    }
}
