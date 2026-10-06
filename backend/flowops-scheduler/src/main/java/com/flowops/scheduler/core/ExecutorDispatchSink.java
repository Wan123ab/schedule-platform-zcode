package com.flowops.scheduler.core;

import com.flowops.domain.dto.query.CredentialDispatchRow;
import com.flowops.domain.mapper.concurrency.CredentialQueryMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.executor.protocol.ExecuteCommand;
import com.flowops.executor.protocol.ExecuteResult;
import com.flowops.scheduler.log.LogIngestService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SSH 下发槽（docs/06 §3.2 ⑦ + §13.2 背压）。
 *
 * <p><b>线程模型</b>：有界线程池（8~32，队列 1000），dispatch() 仅提交任务；
 * 拒绝时按 §13.2 回退——不丢弃（丢弃会造成 SCHEDULING 僵尸步骤），而是按 token 退回
 * WAITING_RESOURCE 让下一 tick 重新调度。</p>
 *
 * <p><b>凭据链</b>：nodeId → 凭据密文（CredentialQueryMapper，仅本路径可读该列）→
 * AES-256-GCM 解密（内存、用完即弃，docs/03 §4.1）→ SSH。</p>
 *
 * <p><b>日志链路</b>：stdout/stderr 行回调 → LogIngestService（缓冲 + 落库 + Pub/Sub）——
 * 双通道（D-10），日志不进主链路。</p>
 */
@Slf4j
public class ExecutorDispatchSink implements DispatchSink {

    private final ExecutorClient executor;
    private final CredentialQueryMapper credentialQuery;
    private final SecretCryptoService crypto;
    private final CompletionBus bus;
    private final TaskStepMapper taskStepMapper;
    private final LogIngestService logIngest;
    private final ThreadPoolExecutor dispatchPool;

    public ExecutorDispatchSink(ExecutorClient executor,
                                CredentialQueryMapper credentialQuery,
                                SecretCryptoService crypto,
                                CompletionBus bus,
                                TaskStepMapper taskStepMapper,
                                LogIngestService logIngest,
                                int corePoolSize, int maxPoolSize, int queueCapacity) {
        this.executor = executor;
        this.credentialQuery = credentialQuery;
        this.crypto = crypto;
        this.bus = bus;
        this.taskStepMapper = taskStepMapper;
        this.logIngest = logIngest;
        // 拒绝策略即背压语义（docs/06 §13.2）：不丢弃，回退状态让调度器重试
        RejectedExecutionHandler backpressure = (task, pool) -> {
            if (task instanceof DispatchTask dispatchTask) {
                log.error("下发池拒绝（背压触发）step={}，回退 WAITING_RESOURCE",
                        dispatchTask.instruction().stepInstanceId());
                taskStepMapper.rollbackClaim(dispatchTask.instruction().taskStepRowId(),
                        dispatchTask.instruction().dispatchToken());
            }
        };
        this.dispatchPool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "flowops-dispatch");
                    t.setDaemon(true);
                    return t;
                },
                backpressure);
    }

    @Override
    public void dispatch(DispatchInstruction instruction) {
        dispatchPool.submit(new DispatchTask(instruction));
    }

    private void doDispatch(DispatchInstruction instruction) {
        try {
            CredentialDispatchRow credential = credentialQuery.findCredentialForDispatch(instruction.nodeId());
            if (credential == null) {
                bus.publish(new Completion(instruction, null, "节点凭据不可用（未绑定或已失效）"));
                return;
            }
            String secret = crypto.decrypt(credential.getSecretEncrypted());
            try {
                ExecuteResult result = executor.execute(ExecuteCommand.builder()
                                .stepInstanceId(instruction.stepInstanceId())
                                .attemptNo(instruction.attemptNo())
                                .dispatchToken(instruction.dispatchToken())
                                .machineIp(instruction.machineIp())
                                .connectType("SSH")
                                .username(credential.getUsername())
                                .secretMaterial(secret)
                                .resolvedCommand(instruction.command())
                                .timeoutSeconds(3600)   // 步骤超时继承链解析在 M4；M1 固定 1h 兜底
                                .successCodes(List.of(0))
                                .build(),
                        // 双通道采集侧（D-10）：行回调 → 缓冲/落库/Pub-Sub；日志不进主链路
                        (streamType, line) -> logIngest.append(instruction.taskRowId(),
                                instruction.taskStepRowId(), instruction.stepInstanceId(),
                                "stdout".equals(streamType) ? "EOF" : "ERR", line));
                bus.publish(new Completion(instruction, result.getExitCode(), result.getFailReason()));
            } finally {
                // secretMaterial 仅存活于本栈帧；置空是防御性写法，明确"用完即弃"的语义
                secret = null;
            }
        } catch (Exception e) {
            // 解密失败等执行前置异常：按"未确认启动"处理（DISPATCH_FAIL）
            log.error("下发前置失败 step={}: {}", instruction.stepInstanceId(), e.getMessage(), e);
            bus.publish(new Completion(instruction, null, "下发前置失败: " + e.getMessage()));
        }
    }

    @PreDestroy
    public void shutdown() {
        dispatchPool.shutdown();
    }

    /** 携带指令的执行任务：拒绝策略需要指令内容做状态回退（§13.2）。 */
    private final class DispatchTask implements Runnable {

        private final DispatchInstruction instruction;

        private DispatchTask(DispatchInstruction instruction) {
            this.instruction = instruction;
        }

        @Override
        public void run() {
            doDispatch(instruction);
        }
    }
}
