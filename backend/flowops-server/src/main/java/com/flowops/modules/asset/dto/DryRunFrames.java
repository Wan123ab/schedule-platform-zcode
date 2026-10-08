package com.flowops.modules.asset.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试运行 SSE 帧构造（docs/07 §7.5 的帧词汇表，不另立方言）。
 *
 * <p><b>为什么复用任务日志的帧类型名</b>：{@code LOG} / {@code EOF} / {@code ERROR}
 * 三种型别、以及 {@code {seq, stream, content}} 的载荷，是 docs/07 §7.5 已经定下的
 * 日志协议。试运行只是"没有 task_step 行"的日志流 —— 若为它另起一套
 * （例如 {@code output} / {@code done}），前端就得为两处日志面板写两套解析。
 * 额外需要的信息（解析后的命令、退出码、耗时）挂在 {@code CMD} / {@code EOF} 帧上，
 * 前者是本场景独有的，后者是原帧的自然扩展。</p>
 *
 * <p><b>为什么集中在这里而不是散在 Controller 里</b>：这是前后端之间的线协议，
 * 属于"改一处要两端同步"的东西。集中成一个可单测的纯函数类，协议变化时
 * 测试会先红，而不是等前端联调时才发现字段名对不上。</p>
 *
 * <p><b>为什么用 {@link LinkedHashMap} 而不是 {@code Map.of}</b>：
 * {@code Map.of} 遇到 null 值直接抛 NPE，而 {@code exit_code = null} 是
 * "进程未能确认启动"这一语义的合法表达（同时 {@code fail_reason} 也会缺席），
 * 必须能带上 null。</p>
 */
public final class DryRunFrames {

    public static final String TYPE_COMMAND = "CMD";
    public static final String TYPE_LOG = "LOG";
    public static final String TYPE_EOF = "EOF";
    public static final String TYPE_ERROR = "ERROR";

    private DryRunFrames() {
    }

    /**
     * 首帧：把"这次到底会执行什么"先告诉用户。
     *
     * <p>{@code command} 是<b>脱敏后</b>的命令 —— 这一帧会经 SSE 出网，
     * 真实命令里的敏感参数值不能出现在这里（M-07）。真实命令只进执行通道。</p>
     */
    public static Map<String, Object> commandFrame(String dryRunId, String nodeId, String nodeName,
                                                   String machineIp, String command, int timeoutSeconds,
                                                   List<Integer> successCodes, Map<String, Object> params,
                                                   Map<String, String> sources) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_COMMAND);
        frame.put("dry_run_id", dryRunId);
        frame.put("node_id", nodeId);
        frame.put("node_name", nodeName);
        frame.put("machine_ip", machineIp);
        frame.put("command", command);
        frame.put("timeout_seconds", timeoutSeconds);
        frame.put("success_codes", successCodes);
        frame.put("params", params);
        frame.put("sources", sources);
        return frame;
    }

    public static Map<String, Object> logFrame(long seq, String stream, String content) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_LOG);
        frame.put("seq", seq);
        frame.put("stream", stream);
        frame.put("content", content);
        return frame;
    }

    /**
     * 终帧：退出码 + 耗时 + 成败判定。
     *
     * @param exitCode null = 进程未能确认启动（连接/认证失败或超时强杀，D-23 边界）
     */
    public static Map<String, Object> eofFrame(Integer exitCode, long durationMs, boolean success, String failReason) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_EOF);
        frame.put("exit_code", exitCode);
        frame.put("duration_ms", durationMs);
        frame.put("success", success);
        frame.put("fail_reason", failReason);
        return frame;
    }

    /** 启动/推送阶段的失败（并发已满、计划阶段异常等）—— 与"命令跑完但失败"区分开。 */
    public static Map<String, Object> errorFrame(int code, String message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_ERROR);
        frame.put("code", code);
        frame.put("message", message);
        return frame;
    }
}
