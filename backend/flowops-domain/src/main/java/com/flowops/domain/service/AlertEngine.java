package com.flowops.domain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.entity.alert.AlertRecord;
import com.flowops.domain.entity.alert.AlertRule;
import com.flowops.domain.mapper.alert.AlertChannelMapper;
import com.flowops.domain.mapper.alert.AlertRecordMapper;
import com.flowops.domain.mapper.alert.AlertRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警引擎（M4-S4，PRD §10.12 / docs/05 §11）。
 *
 * <p><b>为什么在 domain</b>：事件的<b>产生方</b>在 scheduler（任务终态收敛、节点离线标记），
 * 配置管理方在 server —— D-08 下两者唯一的共同依赖终点是 domain。引擎只做三件事：
 * 规则匹配（enabled + eventType + scope）→ 抑制窗口（同 event_type + target_id 的
 * {@code suppress_window_minutes} 内只发一次）→ 逐渠道投递并落 {@code alert_record}。
 * 投递经 {@link AlertTransport} SPI，server 装配真实现。</p>
 *
 * <p><b>触发即落库</b>：即使全部渠道投递失败，记录也落（result=FAILED）——
 * "告警没送达"必须可追溯，否则用户不知道引擎坏没坏（docs/07 §6.5 运维可观测性同款立场）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEngine {

    private final AlertRuleMapper ruleMapper;
    private final AlertChannelMapper channelMapper;
    private final AlertRecordMapper recordMapper;
    private final IdGen idGen;
    private final List<AlertTransport> transports;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 事件入口（事件源调用；同步、异常自行吞掉 —— 告警绝不能拖垮主链路）。
     *
     * @param level CRITICAL / WARN / INFO（PRD §10.12 事件级别表）
     */
    public void fire(String eventType, String targetType, Long targetId, String targetName,
                     Long projectId, Long clusterId, String level, String content) {
        try {
            doFire(eventType, targetType, targetId, targetName, projectId, clusterId, level, content);
        } catch (Exception e) {
            // 告警失败不影响业务主流程（docs/06 的单线程调度契约同样要求 tick 内任何一步不能抛）
            log.error("告警事件处理失败 event={} target={}:{}", eventType, targetType, targetId, e);
        }
    }

    private void doFire(String eventType, String targetType, Long targetId, String targetName,
                        Long projectId, Long clusterId, String level, String content) {
        List<AlertRule> rules = ruleMapper.selectList(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getEnabled, true)
                .eq(AlertRule::getEventType, eventType)
                .eq(AlertRule::getDeleted, false));
        if (rules.isEmpty()) {
            return;
        }
        for (AlertRule rule : rules) {
            if (rule.getProjectId() != null && !rule.getProjectId().equals(projectId)) {
                continue;   // scope 不匹配（null = 全局）
            }
            if (rule.getClusterId() != null && !rule.getClusterId().equals(clusterId)) {
                continue;
            }
            dispatch(rule, eventType, targetType, targetId, targetName, projectId, clusterId, level, content);
        }
    }

    private void dispatch(AlertRule rule, String eventType, String targetType, Long targetId,
                          String targetName, Long projectId, Long clusterId, String level, String content) {
        // 抑制窗口（PRD §10.12：同一对象的同一告警在 N 分钟内只发一次，默认 30 分钟）
        int windowMinutes = rule.getSuppressWindowMinutes() == null ? 30 : rule.getSuppressWindowMinutes();
        AlertRecord last = recordMapper.findLatestFired(eventType, targetId);
        if (last != null && last.getFiredAt() != null
                && last.getFiredAt().isAfter(OffsetDateTime.now().minusMinutes(windowMinutes))) {
            log.debug("告警被抑制 event={} target={} window={}min lastFired={}",
                    eventType, targetId, windowMinutes, last.getFiredAt());
            return;
        }

        // 逐渠道投递（rule.channels 为空 = 无渠道可投 → 记 FAILED，问题对用户可见）
        String[] channels = rule.getChannels() == null ? new String[0] : rule.getChannels();
        List<Map<String, Object>> deliverResult = new ArrayList<>();
        for (String channelType : channels) {
            deliverResult.add(deliverOne(channelType, title(eventType, targetName), content));
        }
        String result = computeResult(deliverResult);

        AlertRecord record = new AlertRecord();
        record.setAlertId(idGen.next("AL", "alert"));
        record.setRuleId(rule.getId());
        record.setEventType(eventType);
        record.setLevel(level == null ? "WARN" : level);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setTargetName(targetName);
        record.setProjectId(projectId);
        record.setClusterId(clusterId);
        record.setContent(content);
        record.setChannels(channels);
        record.setDeliverResult(toJson(deliverResult));
        record.setResult(result);
        record.setFiredAt(OffsetDateTime.now());
        recordMapper.insert(record);
        log.info("告警已触发 alert={} event={} target={}:{} result={}",
                record.getAlertId(), eventType, targetType, targetId, result);
    }

    /** 单渠道投递（渠道未配置/未启用/无实现都记入 deliver_result，不抛出）。 */
    private Map<String, Object> deliverOne(String channelType, String title, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("channel", channelType);
        AlertChannel channel = channelMapper.selectOne(Wrappers.<AlertChannel>lambdaQuery()
                .eq(AlertChannel::getChannelType, channelType));
        if (channel == null || !Boolean.TRUE.equals(channel.getEnabled())) {
            item.put("ok", false);
            item.put("error", "渠道未配置或未启用");
            item.put("latencyMs", 0);
            return item;
        }
        AlertTransport transport = transports.stream()
                .filter(t -> t.supports(channelType))
                .findFirst().orElse(null);
        if (transport == null) {
            item.put("ok", false);
            item.put("error", "该渠道类型无投递实现");
            item.put("latencyMs", 0);
            return item;
        }
        try {
            AlertTransport.SendResult r = transport.send(channel, title, content);
            item.put("ok", r.ok());
            item.put("error", r.error());
            item.put("latencyMs", r.latencyMs());
        } catch (Exception e) {
            item.put("ok", false);
            item.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            item.put("latencyMs", 0);
        }
        return item;
    }

    /** SENT / PARTIAL / FAILED（DDL CHECK；无渠道可投视为 FAILED）。 */
    static String computeResult(List<Map<String, Object>> deliverResult) {
        if (deliverResult.isEmpty()) {
            return "FAILED";
        }
        boolean anyOk = false;
        boolean allOk = true;
        for (Map<String, Object> item : deliverResult) {
            boolean ok = Boolean.TRUE.equals(item.get("ok"));
            anyOk |= ok;
            allOk &= ok;
        }
        return allOk ? "SENT" : anyOk ? "PARTIAL" : "FAILED";
    }

    private String title(String eventType, String targetName) {
        return eventType + (targetName == null || targetName.isBlank() ? "" : " · " + targetName);
    }

    private String toJson(List<Map<String, Object>> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
