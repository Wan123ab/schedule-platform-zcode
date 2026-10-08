package com.flowops.executor.client;

import com.flowops.executor.protocol.ExecuteCommand;
import com.flowops.executor.protocol.ExecuteResult;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSH 执行器单测（O-17：本模块此前零测试，且这 176 行已被两条真实链路使用）。
 *
 * <p><b>这一层测什么、不测什么</b>：JSch 的握手本身只能在真机上验（CI 没有可登录节点），
 * 因此这里用替身驱动 {@code execute} 的<b>全部本地分支</b>——退出码判定、超时强杀、
 * 双流泵、连接/认证失败。这些分支恰恰是"出错时不抛异常、只静默给出错误退出码"的位置，
 * 靠人工点页面几乎测不出来（要造一个能连上却又不挂断、或者认证失败的节点）。</p>
 *
 * <p><b>刻意不依赖运行期环境</b>：不连任何地址、不 sleep 超过 1s（超时用例走
 * timeoutSeconds=1 的最小值），因此可在离线 CI 上稳定复跑。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SshExecutorClientTest {

    private static final String HOST = "10.0.0.1";
    private static final String USER = "flowops";

    @Mock private JSch jsch;
    @Mock private Session session;
    @Mock private ChannelExec channel;

    private SshExecutorClient client;

    /** 行回调收集器：pump 是独立线程，断言按"流 → 行集合"分组，不假设两条流的先后。 */
    private final List<String[]> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = new SshExecutorClient(jsch);
        lines.clear();
    }

    // ── execute ─────────────────────────────────────────────

    @Test
    void 执行成功_退出码0_且stdout与stderr分别按行回调() throws Exception {
        givenChannel(("hello\nworld\n").getBytes(StandardCharsets.UTF_8),
                ("warn-1\n").getBytes(StandardCharsets.UTF_8),
                true, 0);
        ExecuteCommand command = command("echo hello", 30);

        ExecuteResult result = client.execute(command, collector());

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getFailReason()).isNull();
        assertThat(result.getDurationNanos()).isPositive();
        // 派发三元组原样回带：调度侧靠它做 at-least-once 去重（D-23）
        assertThat(result.getStepInstanceId()).isEqualTo("SI-0001");
        assertThat(result.getAttemptNo()).isEqualTo(1);
        assertThat(result.getDispatchToken()).isEqualTo("tok-1");
        assertThat(contentOf("stdout")).containsExactly("hello", "world");
        assertThat(contentOf("stderr")).containsExactly("warn-1");
    }

    /**
     * 非 0 退出码必须<b>原样</b>回带。
     *
     * <p>这里刻意不断言"失败"：成败由调用方拿 {@code success_codes} 判定（PRD §12.4-5），
     * 执行器只负责如实回执。把它写进执行器就等于在两层里各判一次，
     * 将来算子配了 {@code success_codes=[0,1]} 时两边会给出不同结论。</p>
     */
    @Test
    void 非零退出码_原样返回且不填failReason() throws Exception {
        givenChannel(new byte[0], new byte[0], true, 3);

        ExecuteResult result = client.execute(command("exit 3", 30), collector());

        assertThat(result.getExitCode()).isEqualTo(3);
        assertThat(result.getFailReason()).isNull();
    }

    @Test
    void 超时_退出码为null并给出超时原因且断开通道() throws Exception {
        givenChannel(new byte[0], new byte[0], false, 0);

        ExecuteResult result = client.execute(command("sleep 999", 1), collector());

        // null ≠ 非 0：null 是"进程未能确认结束"（DISPATCH_FAIL 语义，D-23 边界）
        assertThat(result.getExitCode()).isNull();
        assertThat(result.getFailReason()).contains("执行超时").contains("1s");
        verify(channel).disconnect();   // 断连 = 远端 SIGHUP；否则进程会留在节点上继续跑
    }

    /**
     * 连接/认证失败 → {@code exitCode = null}，且**不抛异常**。
     *
     * <p>调度侧要按 DISPATCH_FAIL 回退重调度（换节点/退避），因此这里必须返回结果对象
     * 而不是抛出去 —— 抛出去会让"节点连不上"变成"调度线程异常退出"。</p>
     */
    @Test
    void 连接失败_退出码为null且不抛异常() throws Exception {
        when(jsch.getSession(anyString(), anyString(), anyInt()))
                .thenThrow(new JSchException("Auth fail"));

        ExecuteResult result = client.execute(command("echo hi", 30), collector());

        assertThat(result.getExitCode()).isNull();
        assertThat(result.getFailReason()).contains("SSH 连接/执行失败").contains("Auth fail");
        assertThat(lines).isEmpty();       // 进程没起来，一行日志也不该有
    }

    /**
     * 日志通道中途关闭（远端进程被杀）不应把整个 execute 拖挂：把 stderr 造成读取即抛
     * IOException，stdout 仍必须完整送达。这条覆盖的是 pump 的 catch 分支，
     * 也正是"只读 stdout 会在 stderr 塞满缓冲时死锁"那段注释对应的反面。
     */
    @Test
    void 某个流读取中断_不影响另一个流与退出码() throws Exception {
        givenChannel(("row-1\n").getBytes(StandardCharsets.UTF_8), null, true, 0);
        when(channel.getErrStream()).thenReturn(throwingStream());

        ExecuteResult result = client.execute(command("echo row-1", 30), collector());

        assertThat(result.getExitCode()).isZero();
        assertThat(contentOf("stdout")).containsExactly("row-1");
        assertThat(contentOf("stderr")).isEmpty();
    }

    @Test
    void 监听器为null时_执行不受影响() throws Exception {
        givenChannel(("x\n").getBytes(StandardCharsets.UTF_8), new byte[0], true, 0);

        ExecuteResult result = client.execute(command("echo x", 30), null);

        assertThat(result.getExitCode()).isZero();
    }

    /** 中文输出（docs/09 M1 验收场景）：UTF-8 解码不得乱码。 */
    @Test
    void 中文输出按UTF8解码() throws Exception {
        givenChannel("任务完成，共 3 行\n".getBytes(StandardCharsets.UTF_8), new byte[0], true, 0);

        client.execute(command("echo 中文", 30), collector());

        assertThat(contentOf("stdout")).containsExactly("任务完成，共 3 行");
    }

    // ── 凭据注入形态 ────────────────────────────────────────

    @Test
    void 私钥形态凭据_走addIdentity而不是口令() throws Exception {
        givenChannel(new byte[0], new byte[0], true, 0);
        ExecuteCommand command = command("echo hi", 30);
        command.setSecretMaterial("-----BEGIN OPENSSH PRIVATE KEY-----\nxxx");

        client.execute(command, collector());

        verify(jsch).addIdentity(eq("flowops-temp-key-" + HOST),
                ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>isNull(),
                ArgumentMatchers.<byte[]>isNull());
        verify(session, never()).setPassword(anyString());
    }

    @Test
    void 口令形态凭据_走setPassword且不注册私钥() throws Exception {
        givenChannel(new byte[0], new byte[0], true, 0);

        client.execute(command("echo hi", 30), collector());

        verify(session).setPassword("s3cret");
        verify(jsch, never()).addIdentity(anyString(), ArgumentMatchers.<byte[]>any(),
                ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any());
    }

    /** 一期放宽指纹校验（内网节点常未预置 known_hosts）——这条是"别在重构里顺手删掉"的钉子。 */
    @Test
    void 指纹校验放宽_不预置knownHosts也能建连() throws Exception {
        givenChannel(new byte[0], new byte[0], true, 0);

        client.execute(command("echo hi", 30), collector());

        verify(session).setConfig("StrictHostKeyChecking", "no");
    }

    // ── testConnection / terminate ──────────────────────────

    @Test
    void 连通性测试_握手成功返回true并断开连接() throws Exception {
        when(jsch.getSession(anyString(), anyString(), anyInt())).thenReturn(session);
        when(session.isConnected()).thenReturn(true);

        assertThat(client.testConnection(HOST, USER, "pw")).isTrue();
        verify(session).disconnect();
    }

    @Test
    void 连通性测试_异常时返回false而不是抛出() throws Exception {
        when(jsch.getSession(anyString(), anyString(), anyInt())).thenReturn(session);
        org.mockito.Mockito.doThrow(new JSchException("connect timed out"))
                .when(session).connect(anyInt());

        assertThat(client.testConnection(HOST, USER, "pw")).isFalse();
    }

    /**
     * terminate 一期返回 false（E-02：SSH 直跑模型拿不到远端 PID）。
     * 断言这个"明确的否定"而不是让它静默 —— 调用方据此决定是否走人工干预。
     */
    @Test
    void terminate_一期不支持token定位远端进程_返回false() {
        assertThat(client.terminate(HOST, "tok-1")).isFalse();
    }

    @Test
    void 默认成功码为0() {
        assertThat(SshExecutorClient.defaultSuccessCodes()).containsExactly(0);
    }

    // ── 夹具 ────────────────────────────────────────────────

    private ExecuteCommand command(String resolved, int timeoutSeconds) {
        return ExecuteCommand.builder()
                .stepInstanceId("SI-0001")
                .attemptNo(1)
                .dispatchToken("tok-1")
                .machineIp(HOST)
                .connectType("SSH")
                .username(USER)
                .secretMaterial("s3cret")
                .resolvedCommand(resolved)
                .timeoutSeconds(timeoutSeconds)
                .successCodes(SshExecutorClient.defaultSuccessCodes())
                .build();
    }

    private void givenChannel(byte[] stdout, byte[] stderr, boolean closed, int exitStatus) throws Exception {
        when(jsch.getSession(anyString(), anyString(), anyInt())).thenReturn(session);
        when(session.openChannel("exec")).thenReturn(channel);
        when(channel.getInputStream()).thenReturn(new ByteArrayInputStream(stdout));
        when(channel.getErrStream()).thenReturn(stderr == null ? null : new ByteArrayInputStream(stderr));
        when(channel.isClosed()).thenReturn(closed);
        when(channel.getExitStatus()).thenReturn(exitStatus);
        when(channel.isConnected()).thenReturn(true);
    }

    private ExecutorClient.LineListener collector() {
        return (stream, line) -> lines.add(new String[]{stream, line});
    }

    /** 某条流收到的行（pump 线程写、主线程读：execute 返回时两边都已 join）。 */
    private List<String> contentOf(String stream) {
        return lines.stream().filter(l -> stream.equals(l[0])).map(l -> l[1]).toList();
    }

    private static InputStream throwingStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("channel closed by remote");
            }
        };
    }
}
