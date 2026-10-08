package com.flowops.modules.asset.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 试运行 SSE 帧协议单测（docs/07 §7.5 的帧词汇表）。
 *
 * <p>这是前后端之间的线协议：字段名改了前端就得跟着改。把它测起来，
 * 变化时测试先红，而不是等联调时才发现日志面板一片空白。</p>
 */
class DryRunFramesTest {

    @Test
    void 日志帧_字段与任务日志协议一致() {
        Map<String, Object> frame = DryRunFrames.logFrame(7L, "stderr", "WARN 掉了 3 行");

        assertThat(frame).containsEntry("type", "LOG")
                .containsEntry("seq", 7L)
                .containsEntry("stream", "stderr")
                .containsEntry("content", "WARN 掉了 3 行");
    }

    @Test
    void 命令帧_带出脱敏后的命令与溯源() {
        Map<String, Object> frame = DryRunFrames.commandFrame("DR-abc12345", "EN-0001", "node-a",
                "10.0.0.1", "run --pw ***", 600, List.of(0, 1), Map.of("pw", "***"),
                Map.of("pw", "步骤参数"));

        assertThat(frame).containsEntry("type", "CMD")
                .containsEntry("dry_run_id", "DR-abc12345")
                .containsEntry("node_id", "EN-0001")
                .containsEntry("command", "run --pw ***")
                .containsEntry("timeout_seconds", 600)
                .containsEntry("success_codes", List.of(0, 1))
                .containsEntry("sources", Map.of("pw", "步骤参数"));
    }

    @Test
    void 终帧_成功时带退出码0与耗时() {
        Map<String, Object> frame = DryRunFrames.eofFrame(0, 1234L, true, null);

        assertThat(frame).containsEntry("type", "EOF")
                .containsEntry("exit_code", 0)
                .containsEntry("duration_ms", 1234L)
                .containsEntry("success", true)
                .containsEntry("fail_reason", null);
    }

    /**
     * {@code exit_code = null} 必须能被表达出来（"进程未能确认启动"，D-23 边界），
     * 这也是帧构造刻意不用 {@code Map.of}（null 值会抛 NPE）的原因。
     */
    @Test
    void 终帧_进程未确认启动时退出码为null而不是缺席() {
        Map<String, Object> frame = DryRunFrames.eofFrame(null, 300L, false, "SSH 连接/执行失败: Auth fail");

        assertThat(frame).containsKey("exit_code");
        assertThat(frame.get("exit_code")).isNull();
        assertThat(frame).containsEntry("success", false)
                .containsEntry("fail_reason", "SSH 连接/执行失败: Auth fail");
    }

    @Test
    void 错误帧_带业务码与消息() {
        Map<String, Object> frame = DryRunFrames.errorFrame(42900, "试运行并发数已达上限，请稍后重试");

        assertThat(frame).containsEntry("type", "ERROR")
                .containsEntry("code", 42900)
                .containsEntry("message", "试运行并发数已达上限，请稍后重试");
    }
}
