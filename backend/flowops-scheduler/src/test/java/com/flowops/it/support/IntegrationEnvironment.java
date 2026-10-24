package com.flowops.it.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试的环境闸门（与 flowops-server 侧同名同类，内容一致）。
 *
 * <p><b>为什么复制一份而不是共享</b>：scheduler 不依赖 server（D-08 硬约束），
 * 也就不可能引用它的测试类。要为 40 行判定去配一个公共 test-jar，
 * 引入的构建复杂度远超收益。若将来真抽了公共测试模块，这里是第一个该合并的点。</p>
 *
 * <p><b>为什么必须是 Condition 而不是 Assumptions</b>：{@code @SpringBootTest} 的
 * 上下文由 SpringExtension 在 {@code beforeAll} 阶段创建，而 {@code assumeTrue}
 * 写在 {@code @BeforeAll} 里时上下文<b>已经建好了</b> —— 环境缺失会表现为
 * "上下文启动失败"而不是"测试被跳过"。</p>
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
                    "PostgreSQL 不可达（" + host + ":" + pgPort + "）—— 跳过集成测试。");
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
