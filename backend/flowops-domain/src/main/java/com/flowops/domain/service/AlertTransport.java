package com.flowops.domain.service;

import com.flowops.domain.entity.alert.AlertChannel;

/**
 * 告警渠道投递 SPI（M4-S4）。domain 只定义端口，实现在 server
 * （WEBHOOK/WECOM/DINGTALK 真发；EMAIL 一期不接入，见 README-M4 O-65）——
 * scheduler 侧无实现 bean 时 {@code AlertEngine} 按"无投递实现"记 FAILED 通道结果，
 * 只影响送达不影响记录落库。
 */
public interface AlertTransport {

    /** 支持的渠道类型（EMAIL / WECOM / WEBHOOK / DINGTALK，对齐 DDL CHECK）。 */
    String channelType();

    /** 该实现是否承接此渠道类型（一个实现可承接多种同语义渠道，如 webhook 系）。 */
    default boolean supports(String channelType) {
        return channelType().equals(channelType);
    }

    /**
     * 投递一条告警。
     *
     * @param channel 渠道配置（endpoint 已配置；secretEncrypted 需调用方按需解密）
     * @param title   告警标题（事件类型 + 对象名）
     * @param content 告警正文（固定模板：级别/对象/原因/时间/链接，PRD §10.12）
     */
    SendResult send(AlertChannel channel, String title, String content);

    /** 投递结果（latencyMs 供送达结果 JSON 与渠道测试端点复用）。 */
    record SendResult(boolean ok, String error, long latencyMs) {}
}
