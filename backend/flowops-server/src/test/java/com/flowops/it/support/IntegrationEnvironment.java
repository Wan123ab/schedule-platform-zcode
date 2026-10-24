package com.flowops.it.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试的环境闸门（{@code ExecutionCondition}）。
 *
 * <p><b>为什么必须用 Condition 而不是 {@code Assumptions}</b>：{@code @SpringBootTest}
 * 的上下文由 SpringExtension 在 {@code beforeAll} 阶段创建，而 {@code assumeTrue}
 * 写在 {@code @BeforeAll} 里时上下文<b>已经建好了</b> —— 环境缺失会表现为
 * "上下文启动失败"而不是"测试被跳过"。Condition 在容器级求值，早于任何 beforeAll
 * 回调，缺失环境时整类安静跳过（与 scheduler 的 Redis 用例同一个诉求，只是那里
 * 没有 Spring 上下文所以 Assumptions 够用）。</p>
 *
 * <p><b>探测方式</b>：只做 TCP 连通性（1000ms 超时），不建真实连接 —— 目的只是
 * "有没有"，不是"能不能用"。真正的可用性由测试自己失败来暴露。</p>
 */
public class IntegrationEnvironment implements ExecutionCondition {

    private static final int TIMEOUT_MS = 1000;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String host = env("IT_DB_HOST", "127.0.0.1");
        int pgPort = Integer.parseInt(env("IT_DB_PORT", "55432"));
        String redisHost = env("IT_REDIS_HOST", "127.0.0.1");
        int redisPort = Integer.parseInt(env("IT_REDIS_PORT", "6379"));

        if (!reachable(host, pgPort)) {
            return ConditionEvaluationResult.disabled(
                    "PostgreSQL 不可达（" + host + ":" + pgPort + "）—— 跳过集成测试。"
                            + "本机免安装 PG16 的起法见 README-M5 §0；CI 由 postgres service 提供。");
        }
        if (!reachable(redisHost, redisPort)) {
            return ConditionEvaluationResult.disabled(
                    "Redis 不可达（" + redisHost + ":" + redisPort + "）—— 跳过集成测试。");
        }
        return ConditionEvaluationResult.enabled(
                "PG " + host + ":" + pgPort + " + Redis " + redisHost + ":" + redisPort + " 均可达");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
