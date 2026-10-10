package com.flowops.modules.alert.service.transport;

import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.service.AlertTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Webhook 系渠道投递（WEBHOOK / WECOM / DINGTALK 共用 POST JSON 语义，M4-S4）。
 *
 * <p><b>负载格式</b>：{@code {"title": "...", "content": "..."}} —— 通用 Webhook 的
 * 最小约定；企业微信/钉钉机器人的原生格式（msgtype/text 组装）随真实渠道验证时细化
 * （渠道表当前只有 endpoint + secret，原生格式需要的参数语义能装进这两列，不急）。</p>
 *
 * <p><b>超时</b>：5 秒 —— 告警投递在事件源线程内同步执行（fire 吞异常），必须有小上限，
 * 不允许一个挂死的 webhook 拖住调度 tick。</p>
 */
@Slf4j
@Component
public class WebhookAlertTransport implements AlertTransport {

    /** WEBHOOK / WECOM / DINGTALK 都是"POST JSON 到 endpoint"语义 */
    static final java.util.Set<String> SUPPORTED = java.util.Set.of("WEBHOOK", "WECOM", "DINGTALK");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public String channelType() {
        return "WEBHOOK";   // 注册名（WECOM/DINGTALK 由 supports() 承接，见下）
    }

    /** WECOM/DINGTALK 复用本实现的 POST 语义（channelType() 只能返回一个注册名）。 */
    @Override
    public boolean supports(String channelType) {
        return SUPPORTED.contains(channelType);
    }

    @Override
    public SendResult send(AlertChannel channel, String title, String content) {
        long start = System.currentTimeMillis();
        try {
            String body = "{\"title\":" + quote(title) + ",\"content\":" + quote(content) + "}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(channel.getEndpoint()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new SendResult(ok,
                    ok ? null : "HTTP " + response.statusCode(), latency);
        } catch (Exception e) {
            return new SendResult(false,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /** 最小 JSON 字符串转义（标题/正文含引号与换行时不产出非法 JSON）。 */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
