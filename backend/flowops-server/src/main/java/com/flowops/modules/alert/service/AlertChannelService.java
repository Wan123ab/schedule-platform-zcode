package com.flowops.modules.alert.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.mapper.alert.AlertChannelMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.domain.service.AlertTransport;
import com.flowops.modules.alert.dto.AlertChannelVO;
import com.flowops.modules.alert.dto.AlertTestResultVO;
import com.flowops.modules.alert.dto.AlertChannelUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 通知渠道服务（M4-S4，CONTRACT §9）。
 *
 * <p><b>upsert 语义</b>：channel_type UNIQUE + 表初始为空（V5 只种规则不种渠道）——
 * PUT /alert-channels/{type} 对不存在的类型<b>建行</b>（enabled 默认 true），
 * 对已存在的行更新 endpoint/secret。</p>
 *
 * <p><b>secret 永不出网</b>（M-06 同凭据口径）：入参明文 → AES-256-GCM 加密落库；
 * VO 不含 secret 字段。</p>
 */
@Slf4j
@Service
public class AlertChannelService {

    /** DDL CHECK channel_type 4 值 */
    static final Set<String> CHANNEL_TYPES = Set.of("EMAIL", "WECOM", "WEBHOOK", "DINGTALK");

    private final AlertChannelMapper channelMapper;
    private final SecretCryptoService crypto;
    private final List<AlertTransport> transports;

    public AlertChannelService(AlertChannelMapper channelMapper, SecretCryptoService crypto,
                               List<AlertTransport> transports) {
        this.channelMapper = channelMapper;
        this.crypto = crypto;
        this.transports = transports;
    }

    public List<AlertChannelVO> list() {
        // 四个类型都出（未配置的类型给空配置行，前端据此渲染"未配置"态）
        return CHANNEL_TYPES.stream().sorted().map(this::getOrEmpty).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public AlertChannelVO update(String channelType, AlertChannelUpdateRequest request) {
        if (!CHANNEL_TYPES.contains(channelType)) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "未知的渠道类型: " + channelType,
                    java.util.Map.of("allowed", CHANNEL_TYPES));
        }
        AlertChannel channel = requireByTypeOrNull(channelType);
        if (channel == null) {
            channel = new AlertChannel();
            channel.setChannelType(channelType);
            channel.setEnabled(true);
        }
        channel.setEndpoint(request.getEndpoint());
        if (request.getSecret() != null && !request.getSecret().isBlank()) {
            channel.setSecretEncrypted(crypto.encrypt(request.getSecret()));
        }
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);   // version 乐观锁
        }
        log.info("告警渠道已更新 channel={}", channelType);
        return toVO(channel);
    }

    /**
     * 渠道测试发送（CONTRACT §9：{ok, latencyMs, error}）。
     * 结果落 last_test_at/last_test_result —— "verified" 由一次成功测试获得。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertTestResultVO test(String channelType) {
        if (!CHANNEL_TYPES.contains(channelType)) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "未知的渠道类型: " + channelType,
                    java.util.Map.of("allowed", CHANNEL_TYPES));
        }
        AlertChannel channel = requireByTypeOrNull(channelType);
        AlertTransport.SendResult result;
        if (channel == null || !Boolean.TRUE.equals(channel.getEnabled())) {
            result = new AlertTransport.SendResult(false, "渠道未配置或未启用", 0);
        } else {
            AlertTransport transport = transports.stream()
                    .filter(t -> t.supports(channelType))
                    .findFirst().orElse(null);
            result = transport == null
                    ? new AlertTransport.SendResult(false, "该渠道类型无投递实现", 0)
                    : transport.send(channel, "FlowOps 渠道测试", "这是一条来自 FlowOps 的渠道测试消息。");
        }
        if (channel != null) {
            channel.setLastTestAt(OffsetDateTime.now());
            channel.setLastTestResult((result.ok() ? "OK" : "FAILED: " + result.error())
                    + " (" + result.latencyMs() + "ms)");
            if (result.ok()) {
                channel.setVerified(true);
            }
            channelMapper.updateById(channel);
        }
        log.info("告警渠道测试 channel={} ok={} latency={}ms", channelType, result.ok(), result.latencyMs());
        return new AlertTestResultVO(result.ok(), result.latencyMs(), result.error());
    }

    // ── 内部 ────────────────────────────────────────────────

    private AlertChannel requireByTypeOrNull(String channelType) {
        return channelMapper.selectOne(Wrappers.<AlertChannel>lambdaQuery()
                .eq(AlertChannel::getChannelType, channelType));
    }

    private AlertChannelVO getOrEmpty(String channelType) {
        AlertChannel channel = requireByTypeOrNull(channelType);
        AlertChannelVO vo = new AlertChannelVO();
        if (channel == null) {
            vo.setChannelType(channelType);
            vo.setEnabled(false);
            vo.setVerified(false);
            return vo;
        }
        return toVO(channel);
    }

    private AlertChannelVO toVO(AlertChannel channel) {
        AlertChannelVO vo = new AlertChannelVO();
        vo.setChannelType(channel.getChannelType());
        vo.setEndpoint(channel.getEndpoint());
        vo.setEnabled(channel.getEnabled());
        vo.setVerified(channel.getVerified());
        vo.setLastTestAt(channel.getLastTestAt());
        vo.setLastTestResult(channel.getLastTestResult());
        return vo;
    }
}
