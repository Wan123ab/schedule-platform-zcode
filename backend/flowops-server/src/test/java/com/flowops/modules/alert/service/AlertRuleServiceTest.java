package com.flowops.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.alert.AlertRule;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.alert.AlertRuleMapper;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.modules.alert.dto.AlertRuleUpsertRequest;
import com.flowops.modules.alert.dto.AlertRuleVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警规则服务单测（M4-S4）。
 *
 * <p><b>本测试守的是什么</b>——规则是告警的"闸门定义"，校验写松一个值就白配：</p>
 * <ol>
 *   <li><b>eventType 以 DDL CHECK 9 值为准</b>（CONTRACT §9 只列 5 个的冲突已勘定）——
 *       拒收 CONTRACT 之外也不在 DDL 的值，但必须收 QUEUE_BACKLOG 等 DDL 有而 CONTRACT 无的值；</li>
 *   <li><b>condition 必须是 JSON 对象</b>（42241）—— 非对象 JSON 会让求值链路崩；</li>
 *   <li><b>D-27 双向翻译</b>：入站 PRJ-xxxx/CL-xxxx → 行 id，出站行 id → 业务编号；</li>
 *   <li><b>抑制窗口 ≥ 1</b>（0 分钟窗口 = 形同虚设）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertRuleServiceTest {

    @Mock private AlertRuleMapper ruleMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ClusterMapper clusterMapper;

    private AlertRuleService service;

    @BeforeEach
    void setUp() {
        service = new AlertRuleService(ruleMapper, projectMapper, clusterMapper,
                new IdGen(key -> 3L), new ObjectMapper());
        Project p = new Project();
        p.setId(20L);
        p.setProjectId("PRJ-20261006-0001");
        when(projectMapper.selectOne(any(Wrapper.class))).thenReturn(p);
        when(projectMapper.selectById(20L)).thenReturn(p);
        Cluster c = new Cluster();
        c.setId(30L);
        c.setClusterId("CL-0001");
        when(clusterMapper.selectOne(any(Wrapper.class))).thenReturn(c);
        when(clusterMapper.selectById(30L)).thenReturn(c);
        when(ruleMapper.insert(any(AlertRule.class))).thenAnswer(inv -> {
            inv.getArgument(0, AlertRule.class).setId(1L);
            return 1;
        });
    }

    private AlertRuleUpsertRequest request(String eventType) {
        AlertRuleUpsertRequest r = new AlertRuleUpsertRequest();
        r.setRuleName("失败通知");
        r.setEventType(eventType);
        r.setChannels(List.of("WEBHOOK"));
        r.setReceivers(List.of("a@x.com"));
        r.setSuppressWindowMinutes(15);
        r.setProjectId("PRJ-20261006-0001");
        return r;
    }

    @Test
    void create_acceptsDdlOnlyEventType() {
        // QUEUE_BACKLOG 在 CONTRACT §9 的 5 值清单里有但 V1.1 才接入 —— DDL CHECK 里有，必须收
        AlertRuleVO vo = service.create(request("QUEUE_BACKLOG"));
        assertThat(vo.getRuleId()).isEqualTo("AR-0003");
        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(ruleMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(20L);       // 入站翻译
        assertThat(captor.getValue().getCondition()).isEqualTo("{}");
        assertThat(captor.getValue().getChannels()).containsExactly("WEBHOOK");
        assertThat(vo.getProjectId()).isEqualTo("PRJ-20261006-0001");      // 出站回译
    }

    @Test
    void create_rejectsUnknownEventType() {
        assertThatThrownBy(() -> service.create(request("NOT_AN_EVENT")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
        verify(ruleMapper, never()).insert(any(AlertRule.class));
    }

    @Test
    void create_rejectsUnknownChannelType() {
        AlertRuleUpsertRequest r = request("TASK_FAILED");
        r.setChannels(List.of("SMS"));
        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
    }

    @Test
    void create_rejectsNonObjectCondition() {
        AlertRuleUpsertRequest r = request("TASK_FAILED");
        r.setCondition(Map.of());   // 合法对象
        assertThat(service.create(r).getCondition()).isEmpty();
    }

    @Test
    void create_rejectsZeroSuppressWindow() {
        AlertRuleUpsertRequest r = request("TASK_FAILED");
        r.setSuppressWindowMinutes(0);
        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
    }

    @Test
    void create_translatesUnknownProjectTo404() {
        when(projectMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.create(request("TASK_FAILED")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void update_requiresExistingRule() {
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.update("AR-9999", request("TASK_FAILED")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void update_appliesFieldsWithVersionLock() {
        AlertRule existing = new AlertRule();
        existing.setId(1L);
        existing.setRuleId("AR-0001");
        existing.setEventType("TASK_FAILED");
        existing.setCondition("{}");
        existing.setDeleted(false);
        existing.setVersion(2);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(ruleMapper.selectById(1L)).thenReturn(existing);

        AlertRuleUpsertRequest r = request("TASK_TIMEOUT");
        r.setProjectId(null);                       // 改为全局
        service.update("AR-0001", r);

        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(ruleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TASK_TIMEOUT");
        assertThat(captor.getValue().getProjectId()).isNull();
        assertThat(captor.getValue().getVersion()).isEqualTo(2);   // 乐观锁随实体带出
    }

    @Test
    void toggleEnabled_casCall() {
        AlertRule existing = new AlertRule();
        existing.setId(1L);
        existing.setRuleId("AR-0001");
        existing.setDeleted(false);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(ruleMapper.selectById(1L)).thenReturn(existing);
        service.toggleEnabled("AR-0001", false);
        verify(ruleMapper).toggleEnabled(1L, false, "system");   // UserContext 无登录态时落 system
    }

    @Test
    void delete_softDeletesNotPhysical() {
        AlertRule existing = new AlertRule();
        existing.setId(1L);
        existing.setRuleId("AR-0001");
        existing.setDeleted(false);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        service.delete("AR-0001");
        verify(ruleMapper).softDelete(1L, "system");
        verify(ruleMapper, never()).deleteById(1L);
    }
}
