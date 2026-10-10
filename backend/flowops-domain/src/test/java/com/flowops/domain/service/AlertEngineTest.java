package com.flowops.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.entity.alert.AlertRecord;
import com.flowops.domain.entity.alert.AlertRule;
import com.flowops.domain.mapper.alert.AlertChannelMapper;
import com.flowops.domain.mapper.alert.AlertRecordMapper;
import com.flowops.domain.mapper.alert.AlertRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警引擎单测（M4-S4，PRD §10.12）。
 *
 * <p><b>本测试守的是什么</b>——告警引擎是自动触达用户的最后防线：</p>
 * <ol>
 *   <li><b>抑制窗口</b>：同 event_type + target_id 在 suppress_window_minutes 内只发一次
 *       （默认 30 分钟）—— 没有它，120 个任务的回填一夜能炸出上千条通知；</li>
 *   <li><b>scope 匹配</b>：项目/集群限定规则只对匹配 scope 的事件生效，null = 全局；</li>
 *   <li><b>投递失败必须落记录</b>（result=FAILED/PARTIAL）—— "没送达"必须可追溯；</li>
 *   <li><b>fire 自吞异常</b>：告警绝不拖垮事件源（调度 tick 单线程契约）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertEngineTest {

    @Mock private AlertRuleMapper ruleMapper;
    @Mock private AlertChannelMapper channelMapper;
    @Mock private AlertRecordMapper recordMapper;
    @Mock private AlertTransport transport;

    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AlertEngine(ruleMapper, channelMapper, recordMapper,
                new IdGen(key -> 7L), List.of(transport));
    }

    // ── 夹具 ────────────────────────────────────────────────

    private AlertRule rule(String eventType, Long projectId, Long clusterId, int window) {
        AlertRule r = new AlertRule();
        r.setId(1L);
        r.setRuleId("AR-0001");
        r.setRuleName("任务失败通知");
        r.setEventType(eventType);
        r.setCondition("{}");
        r.setChannels(new String[]{"WEBHOOK"});
        r.setReceivers("[]");
        r.setSuppressWindowMinutes(window);
        r.setProjectId(projectId);
        r.setClusterId(clusterId);
        r.setEnabled(true);
        r.setDeleted(false);
        return r;
    }

    private AlertChannel channel(boolean enabled) {
        AlertChannel c = new AlertChannel();
        c.setId(5L);
        c.setChannelType("WEBHOOK");
        c.setEndpoint("https://hooks.example.com/x");
        c.setEnabled(enabled);
        return c;
    }

    @SuppressWarnings("unchecked")
    private void stubRules(AlertRule... rules) {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rules));
    }

    // ── 规则匹配与 scope ────────────────────────────────────

    @Test
    void fire_matchingRule_deliversAndRecords() {
        stubRules(rule("TASK_FAILED", null, null, 30));
        when(channelMapper.selectOne(any())).thenReturn(channel(true));
        when(transport.supports("WEBHOOK")).thenReturn(true);
        when(transport.send(any(), anyString(), anyString()))
                .thenReturn(new AlertTransport.SendResult(true, null, 88));

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-20261011-0001", 20L, null, "CRITICAL", "任务失败");

        ArgumentCaptor<AlertRecord> captor = ArgumentCaptor.forClass(AlertRecord.class);
        verify(recordMapper).insert(captor.capture());
        AlertRecord record = captor.getValue();
        assertThat(record.getAlertId()).isEqualTo("AL-0007");
        assertThat(record.getEventType()).isEqualTo("TASK_FAILED");
        assertThat(record.getLevel()).isEqualTo("CRITICAL");
        assertThat(record.getTargetType()).isEqualTo("TASK");
        assertThat(record.getResult()).isEqualTo("SENT");
        assertThat(record.getDeliverResult()).contains("\"ok\":true").contains("88");
    }

    @Test
    void fire_projectScopedRule_skipsOtherProjectEvent() {
        stubRules(rule("TASK_FAILED", 20L, null, 30));
        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-20261011-0001", 21L, null, "CRITICAL", "x");
        verify(recordMapper, never()).insert(any(AlertRecord.class));
    }

    @Test
    void fire_nullScopeEvent_matchesProjectRule() {
        // 事件没带 projectId（历史遗留路径）时只匹配全局规则 —— 限定规则绝不误发
        stubRules(rule("TASK_FAILED", 20L, null, 30));
        when(channelMapper.selectOne(any())).thenReturn(channel(true));
        when(transport.supports("WEBHOOK")).thenReturn(true);
        when(transport.send(any(), anyString(), anyString()))
                .thenReturn(new AlertTransport.SendResult(true, null, 1));
        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", null, null, "CRITICAL", "x");
        verify(recordMapper, never()).insert(any(AlertRecord.class));
    }

    // ── 抑制窗口 ────────────────────────────────────────────

    @Test
    void fire_withinSuppressWindow_skips() {
        stubRules(rule("TASK_FAILED", null, null, 30));
        AlertRecord last = new AlertRecord();
        last.setFiredAt(OffsetDateTime.now().minusMinutes(5));
        when(recordMapper.findLatestFired("TASK_FAILED", 9L)).thenReturn(last);

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", 20L, null, "CRITICAL", "x");
        verify(recordMapper, never()).insert(any(AlertRecord.class));
        verify(transport, never()).send(any(), anyString(), anyString());
    }

    @Test
    void fire_beyondSuppressWindow_sendsAgain() {
        stubRules(rule("TASK_FAILED", null, null, 30));
        AlertRecord last = new AlertRecord();
        last.setFiredAt(OffsetDateTime.now().minusMinutes(31));
        when(recordMapper.findLatestFired("TASK_FAILED", 9L)).thenReturn(last);
        when(channelMapper.selectOne(any())).thenReturn(channel(true));
        when(transport.supports("WEBHOOK")).thenReturn(true);
        when(transport.send(any(), anyString(), anyString()))
                .thenReturn(new AlertTransport.SendResult(true, null, 3));

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", 20L, null, "CRITICAL", "x");
        verify(recordMapper).insert(any(AlertRecord.class));
    }

    @Test
    void fire_sameEventDifferentTarget_notSuppressed() {
        // 抑制键是 event_type + target_id：不同任务失败互不抑制
        stubRules(rule("TASK_FAILED", null, null, 30));
        when(recordMapper.findLatestFired("TASK_FAILED", 9L)).thenReturn(null);
        when(channelMapper.selectOne(any())).thenReturn(channel(true));
        when(transport.supports("WEBHOOK")).thenReturn(true);
        when(transport.send(any(), anyString(), anyString()))
                .thenReturn(new AlertTransport.SendResult(true, null, 3));

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-A", 20L, null, "CRITICAL", "x");
        verify(recordMapper).insert(any(AlertRecord.class));
    }

    // ── 投递失败语义 ────────────────────────────────────────

    @Test
    void fire_channelNotConfigured_recordsFailed() {
        stubRules(rule("TASK_FAILED", null, null, 30));
        when(channelMapper.selectOne(any())).thenReturn(null);   // 渠道表初始为空

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", 20L, null, "CRITICAL", "x");
        ArgumentCaptor<AlertRecord> captor = ArgumentCaptor.forClass(AlertRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
        assertThat(captor.getValue().getDeliverResult()).contains("渠道未配置或未启用");
    }

    @Test
    void fire_partialDelivery_recordsPartial() {
        stubRules(rule("TASK_FAILED", null, null, 30));
        AlertChannel webhook = channel(true);
        when(channelMapper.selectOne(any())).thenReturn(webhook);
        when(transport.supports("WEBHOOK")).thenReturn(true);
        when(transport.supports("EMAIL")).thenReturn(false);
        when(transport.send(any(), anyString(), anyString()))
                .thenReturn(new AlertTransport.SendResult(true, null, 10));

        // 规则引用 EMAIL + WEBHOOK，EMAIL 无实现 → PARTIAL
        AlertRule r = rule("TASK_FAILED", null, null, 30);
        r.setChannels(new String[]{"EMAIL", "WEBHOOK"});
        stubRules(r);

        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", 20L, null, "CRITICAL", "x");
        ArgumentCaptor<AlertRecord> captor = ArgumentCaptor.forClass(AlertRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("PARTIAL");
    }

    // ── 自吞异常 ────────────────────────────────────────────

    @Test
    void fire_mapperThrows_neverPropagates() {
        when(ruleMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        // 不抛出 —— 告警失败不拖垮事件源
        engine.fire("TASK_FAILED", "TASK", 9L, "TASK-1", 20L, null, "CRITICAL", "x");
        verify(recordMapper, never()).insert(any(AlertRecord.class));
    }

    // ── result 计算口径 ─────────────────────────────────────

    @Test
    void computeResult_semantics() {
        assertThat(AlertEngine.computeResult(List.of())).isEqualTo("FAILED");
        assertThat(AlertEngine.computeResult(List.of(
                java.util.Map.of("ok", true)))).isEqualTo("SENT");
        assertThat(AlertEngine.computeResult(List.of(
                java.util.Map.of("ok", true), java.util.Map.of("ok", false)))).isEqualTo("PARTIAL");
        assertThat(AlertEngine.computeResult(List.of(
                java.util.Map.of("ok", false)))).isEqualTo("FAILED");
    }
}
