package com.flowops.modules.asset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ErrorCode;
import com.flowops.executor.protocol.ExecuteResult;
import com.flowops.modules.asset.dto.DryRunFrames;
import com.flowops.modules.asset.dto.DryRunRequest;
import com.flowops.modules.asset.service.OperatorDryRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 算子试运行接口（PRD §10.6；帧协议见 {@link DryRunFrames}）。
 *
 * <p><b>为什么是 SSE 而不是 WebSocket</b>：任务日志用 WebSocket 是因为它需要
 * "断线按 offset 续传 + 多客户端订阅同一份落库日志"（docs/07 §7.5）；
 * 而试运行是一次性的、不落库的、单客户端的过程流 —— 引入 WebSocket 会话管理
 * 只会给一个 10 分钟上限的短过程加上长连接的心跳/鉴权/续传负担。SSE 天然是
 * "一次请求一段流"，且能复用本项目的 Sa-Token 头部鉴权（不需要像 WebSocket
 * 那样把 token 放到 query 上）。</p>
 *
 * <p><b>为什么路径是 POST 而不是 GET</b>：参数值走请求体。放 query 会把
 * 敏感参数（{@code sensitive=true}）写进访问日志、代理日志与浏览器历史，
 * 那样"参数脱敏"就白做了。浏览器端用 {@code fetch} + {@code ReadableStream} 消费，
 * 不要用 {@code EventSource}（它只支持 GET 且不能带自定义头部）。</p>
 *
 * <p><b>两条错误通道的分工（前端需要分别处理）</b>：</p>
 * <ul>
 *   <li><b>建流之前</b>（校验失败 42210 / 越权 40301 / 节点不可用 42200 / 命令引用落空 42214）：
 *       普通 JSON 响应 + HTTP 状态码 —— 这类错误用户要改表单，不该混在日志面板里；</li>
 *   <li><b>建流之后</b>（并发已满、执行期异常）：SSE 的 {@code ERROR} 帧 —— 此时
 *       响应头已经发出去了，改不了状态码，只能按协议在流内报错。</li>
 * </ul>
 *
 * <p><b>本类不做鉴权之外的任何判定</b>：{@code plan()} 在请求线程上完成全部
 * 数据范围判定（原因见 {@link OperatorDryRunService} 的类注释），
 * 因此工作线程只负责推流。</p>
 */
@Slf4j
@RestController
public class OperatorDryRunController {

    /** 并发已满 → 42900（限流语义；HTTP 层也确实是"太多并发请求"）。 */
    private static final int CODE_TOO_MANY_RUNS = ErrorCode.RATE_LIMITED.getCode();

    private final OperatorDryRunService dryRunService;
    private final ObjectMapper objectMapper;
    private final Executor dryRunExecutor;

    /**
     * 显式构造器（不用 {@code @RequiredArgsConstructor}）：需要给 {@code Executor}
     * 加 {@code @Qualifier} —— 容器里已有 {@code auditExecutor}，两个 {@code Executor}
     * 候选是按类型注入无法区分的。本仓库的 {@code lombok.config} 关了配置冒泡且没有
     * 打开 copyableAnnotations，故注解写在字段上不会被带到构造参数上。
     */
    public OperatorDryRunController(OperatorDryRunService dryRunService,
                                    ObjectMapper objectMapper,
                                    @Qualifier("dryRunExecutor") Executor dryRunExecutor) {
        this.dryRunService = dryRunService;
        this.objectMapper = objectMapper;
        this.dryRunExecutor = dryRunExecutor;
    }

    /**
     * 试运行（必审动作 {@code DRYRUN_OPERATOR}，docs/07 §7.3；权限点 {@code schedule:operator:dryrun}）。
     *
     * <p>响应是 {@code text/event-stream}：首帧 {@code CMD}（脱敏后的完整命令 + 溯源），
     * 随后逐行 {@code LOG}，最后 {@code EOF}（退出码 + 耗时 + 成败）。</p>
     *
     * <p><b>刻意不写 {@code produces = TEXT_EVENT_STREAM_VALUE}</b>：那会把本端点的可接受
     * 响应类型钉死，于是上面说的第一类错误（JSON 错误体）会被
     * {@code HttpMediaTypeNotAcceptableException}（406）顶掉，用户看到的就成了
     * "媒体类型不被接受"而不是"第 3 行参数必填"。返回 {@code SseEmitter} 本身已让
     * Spring 用 {@code text/event-stream} 输出成功路径。</p>
     */
    @PostMapping("/operator-versions/{versionId}/dry-run")
    @RequiresPermission("schedule:operator:dryrun")
    @Audited(action = "DRYRUN_OPERATOR", targetType = "OPERATOR_VERSION", targetIdExpr = "#versionId")
    public SseEmitter dryRun(@PathVariable String versionId, @RequestBody DryRunRequest request) {
        OperatorDryRunService.DryRunPlan plan = dryRunService.plan(versionId, request);

        // 超时留 30s 余量：执行侧的硬超时（plan.timeoutSeconds）先到，
        // 由它给出带"执行超时"原因的 EOF 帧，而不是让 SSE 连接先被断开
        SseEmitter emitter = new SseEmitter((plan.timeoutSeconds() + 30) * 1000L);
        emitter.onTimeout(() -> {
            log.warn("试运行流超时被关闭 dry_run={}", plan.dryRunId());
            send(emitter, DryRunFrames.errorFrame(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode(),
                    "试运行流超时，连接已关闭"));
            emitter.complete();
        });
        emitter.onError(e -> log.debug("试运行流异常 dry_run={}: {}", plan.dryRunId(), e.getMessage()));

        // 首帧在请求线程上发出：保证 CMD 一定排在第一条 LOG 之前（否则用户会先看到
        // 日志、后看到"这条命令是什么"，短命令的场景下等于顺序颠倒）
        send(emitter, DryRunFrames.commandFrame(plan.dryRunId(), plan.nodeId(), plan.nodeName(),
                plan.machineIp(), plan.displayCommand(), plan.timeoutSeconds(),
                plan.successCodes(), plan.snapshotParams(), plan.sources()));

        try {
            dryRunExecutor.execute(() -> stream(plan, emitter));
        } catch (RejectedExecutionException e) {
            log.warn("试运行并发已满，拒绝 dry_run={}", plan.dryRunId());
            send(emitter, DryRunFrames.errorFrame(CODE_TOO_MANY_RUNS, "试运行并发数已达上限，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    /** 工作线程：执行 + 逐行推流。全程不查库、不判权限（见类注释）。 */
    private void stream(OperatorDryRunService.DryRunPlan plan, SseEmitter emitter) {
        AtomicLong seq = new AtomicLong();
        try {
            ExecuteResult result = dryRunService.run(plan, (stream, line) ->
                    send(emitter, DryRunFrames.logFrame(seq.incrementAndGet(), stream, line)));

            long durationMs = result.getDurationNanos() == null ? 0L : result.getDurationNanos() / 1_000_000;
            send(emitter, DryRunFrames.eofFrame(result.getExitCode(), durationMs,
                    OperatorDryRunService.isSuccess(result.getExitCode(), plan.successCodes()),
                    result.getFailReason()));
        } catch (Exception e) {
            log.error("试运行执行期异常 dry_run={}", plan.dryRunId(), e);
            send(emitter, DryRunFrames.errorFrame(ErrorCode.INTERNAL_ERROR.getCode(),
                    "试运行执行失败: " + e.getMessage()));
        } finally {
            emitter.complete();
        }
    }

    /**
     * 发送一帧。
     *
     * <p>发送失败只记日志不抛出：客户端关掉页面之后每一次 send 都会抛 ——
     * 那不该把工作线程的堆栈刷满，也不该影响"SSH 上那条命令"的收尾
     * （一期无法终止远端进程，只能让它跑完或等超时）。</p>
     */
    private void send(SseEmitter emitter, Map<String, Object> frame) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(frame),
                    MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 帧发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
