package com.flowops.config;

import com.flowops.modules.task.ws.LogPushRegistry;
import com.flowops.modules.task.ws.TaskLogWebSocket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 与日志订阅配置（docs/07 §7.5 / D-10）。
 *
 * <p>链路：scheduler 的 LogIngestService 发布到 {@code flowops:log:stream:{stepRowId}} →
 * 本配置的 Redis 模式订阅 → LogPushRegistry 按会话转发。日志不进主链路（D-10），
 * 无人订阅时帧只落库、不推送（重连按 offset 补）。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    public static final String LOG_CHANNEL_PATTERN = "flowops:log:stream:*";

    /** JSR-356 端点导出（Spring Boot 内嵌容器必须显式导出，否则 @ServerEndpoint 不生效）。 */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    /** 端点由容器实例化，注册表用静态桥注入（JSR-356 与 Spring 生命周期的接缝）。 */
    @Bean
    public Object bindLogRegistry(LogPushRegistry registry) {
        TaskLogWebSocket.bind(registry);
        return new Object();
    }

    /** Redis 模式订阅：日志帧 → 会话转发。 */
    @Bean
    public RedisMessageListenerContainer logListenerContainer(RedisConnectionFactory factory,
                                                              LogPushRegistry registry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            try {
                String channel = new String(message.getChannel(),
                        java.nio.charset.StandardCharsets.UTF_8);
                // channel 形如 flowops:log:stream:{stepRowId}
                long stepRowId = Long.parseLong(channel.substring(channel.lastIndexOf(':') + 1));
                String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                registry.publish(stepRowId, body);
            } catch (Exception e) {
                log.warn("日志帧转发失败（忽略单帧）: {}", e.getMessage());
            }
        }, new PatternTopic(LOG_CHANNEL_PATTERN));
        return container;
    }
}
