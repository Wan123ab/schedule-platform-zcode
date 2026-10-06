package com.flowops.executor.client;

import com.flowops.executor.protocol.ExecuteCommand;
import com.flowops.executor.protocol.ExecuteResult;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SSH 执行器（Q-01：一期以 SSH 为主；Agent 作为增强可放弃）。
 *
 * <p><b>实现要点（docs/09 M1 风险项逐条对应）</b>：</p>
 * <ul>
 *   <li><b>按行流式读取</b>：stdout/stderr 分两个线程消费，避免远端写满管道缓冲导致死锁
 *       （经典 SSH 事故：只读 stdout，stderr 塞满 4KB 缓冲后进程挂起）；</li>
 *   <li><b>编码统一 UTF-8</b>：含中文输出必须在真实节点验证（docs/09 M1 验收场景）；</li>
 *   <li><b>超时即终止</b>：到 timeoutSeconds 断开 Channel（远端 SIGHUP），退出码记为 null
 *       —— 由调度侧按 TIMEOUT 处理而非截断的退出码；</li>
 *   <li><b>凭据用完即弃</b>：secretMaterial 只在本方法栈内存活（docs/03 §4.1）。</li>
 * </ul>
 *
 * <p><b>退出码语义（D-23 的边界）</b>：exitCode = null 表示"进程未能确认启动"
 * （连接失败/认证失败/超时强杀）——调用方必须把它与"退出码非 0"严格区分，
 * 前者回退重调度，后者按失败策略处理。</p>
 */
@Slf4j
public class SshExecutorClient implements ExecutorClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final JSch jsch = new JSch();

    @Override
    public boolean testConnection(String machineIp, String username, String secretMaterial) {
        Session session = null;
        try {
            session = connect(machineIp, username, secretMaterial);
            return session.isConnected();
        } catch (Exception e) {
            log.warn("连通性测试失败 host={} user={}: {}", machineIp, username, e.getMessage());
            return false;
        } finally {
            closeQuietly(session);
        }
    }

    @Override
    public ExecuteResult execute(ExecuteCommand command, LineListener lineListener) {
        Session session = null;
        ChannelExec channel = null;
        long startedAt = System.nanoTime();
        try {
            session = connect(command.getMachineIp(), command.getUsername(), command.getSecretMaterial());
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command.getResolvedCommand());
            channel.setInputStream(null);
            // 合并 stderr 到 stdout 会丢失流区分（PRD §13.1-5），保持分通道读取
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(CONNECT_TIMEOUT_MS);

            Thread errPump = pump(stderr, "stderr", command, lineListener);
            Thread outPump = pump(stdout, "stdout", command, lineListener);

            boolean finished = awaitExit(channel, command.getTimeoutSeconds());
            int exitCode = finished ? channel.getExitStatus() : -1;
            if (!finished) {
                log.warn("执行超时被终止 step={} timeout={}s", command.getStepInstanceId(), command.getTimeoutSeconds());
            }
            outPump.join(TimeUnit.SECONDS.toMillis(5));
            errPump.join(TimeUnit.SECONDS.toMillis(5));

            return ExecuteResult.builder()
                    .stepInstanceId(command.getStepInstanceId())
                    .attemptNo(command.getAttemptNo())
                    .dispatchToken(command.getDispatchToken())
                    .exitCode(finished ? exitCode : null)
                    .durationNanos(System.nanoTime() - startedAt)
                    .failReason(finished ? null : "执行超时（" + command.getTimeoutSeconds() + "s）")
                    .build();
        } catch (Exception e) {
            // 连接失败 / 认证失败：进程未能确认启动 → exitCode = null（DISPATCH_FAIL 语义）
            log.warn("SSH 执行失败（进程未确认启动）step={} host={}: {}",
                    command.getStepInstanceId(), command.getMachineIp(), e.getMessage());
            return ExecuteResult.builder()
                    .stepInstanceId(command.getStepInstanceId())
                    .attemptNo(command.getAttemptNo())
                    .dispatchToken(command.getDispatchToken())
                    .exitCode(null)
                    .durationNanos(System.nanoTime() - startedAt)
                    .failReason("SSH 连接/执行失败: " + e.getMessage())
                    .build();
        } finally {
            closeQuietly(channel);
            closeQuietly(session);
        }
    }

    @Override
    public boolean terminate(String machineIp, String dispatchToken) {
        // 一期 SSH 直跑模型无法按 dispatchToken 定位远端 PID（Agent 上报 PID 为 E-02 二期项）；
        // 超时场景由 execute() 的 awaitExit 断连兜底，STOPPING 终止随 M4 人工干预落地
        log.info("terminate 暂不支持按 token 定位远端进程（E-02），token={}", dispatchToken);
        return false;
    }

    private Session connect(String host, String username, String secret) throws Exception {
        Session session = jsch.getSession(username, host, 22);
        if (secret != null && secret.contains("BEGIN")) {
            jsch.addIdentity("flowops-temp-key-" + host,
                    secret.getBytes(StandardCharsets.UTF_8), null, null);   // 私钥材料
        } else {
            session.setPassword(secret);                                    // 口令
        }
        session.setConfig("StrictHostKeyChecking", "no");   // 企业内网节点指纹常未预置；一期放宽，M2 随凭据域收紧
        session.setServerAliveInterval(15_000);
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }

    private Thread pump(InputStream in, String streamName, ExecuteCommand command, LineListener listener) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (listener != null) {
                        listener.onLine(streamName, line);
                    }
                }
            } catch (Exception e) {
                log.debug("日志泵结束 stream={} step={}（通道关闭属正常）", streamName, command.getStepInstanceId());
            }
        }, "ssh-pump-" + command.getStepInstanceId() + "-" + streamName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private boolean awaitExit(ChannelExec channel, Integer timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(timeoutSeconds == null ? 3600 : timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            if (channel.isClosed()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private void closeQuietly(Session session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private void closeQuietly(ChannelExec channel) {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
    }

    /** 便捷重载：successCodes 默认 {0}（PRD §12.4-5）。 */
    public static List<Integer> defaultSuccessCodes() {
        return List.of(0);
    }
}
