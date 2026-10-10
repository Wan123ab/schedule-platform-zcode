package com.flowops.modules.alert.service.transport;

import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.service.AlertTransport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道投递实现单测（M4-S4）。
 *
 * <p>不真打外网：连接失败/HTTP 4xx/5xx 都必须收敛为 {@code ok=false} 带错误信息 ——
 * 投递结果写进 deliver_result，绝不允许抛出穿透到告警引擎。</p>
 */
class AlertTransportImplTest {

    @Test
    void webhook_supportsWebhookFamily() {
        WebhookAlertTransport t = new WebhookAlertTransport();
        assertThat(t.supports("WEBHOOK")).isTrue();
        assertThat(t.supports("WECOM")).isTrue();
        assertThat(t.supports("DINGTALK")).isTrue();
        assertThat(t.supports("EMAIL")).isFalse();
    }

    @Test
    void webhook_sendToUnreachableEndpoint_returnsOkFalseWithError() {
        WebhookAlertTransport t = new WebhookAlertTransport();
        AlertChannel channel = new AlertChannel();
        channel.setChannelType("WEBHOOK");
        channel.setEndpoint("http://127.0.0.1:9/none");   // 端口 9（discard）几乎必然连接失败
        AlertTransport.SendResult result = t.send(channel, "标题", "正文");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void webhook_quoteEscapesJsonSpecials() {
        assertThat(WebhookAlertTransport.quote("he\"llo")).isEqualTo("\"he\\\"llo\"");
        assertThat(WebhookAlertTransport.quote("a\nb\\c")).isEqualTo("\"a\\nb\\\\c\"");
        assertThat(WebhookAlertTransport.quote("中文")).isEqualTo("\"中文\"");
        assertThat(WebhookAlertTransport.quote("tab\there")).isEqualTo("\"tab\\there\"");
    }

    @Test
    void email_placeholderAlwaysFailsWithReason() {
        EmailAlertTransport t = new EmailAlertTransport();
        AlertChannel channel = new AlertChannel();
        channel.setChannelType("EMAIL");
        channel.setEndpoint("smtp://example.com");
        AlertTransport.SendResult result = t.send(channel, "标题", "正文");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("SMTP");
    }
}
