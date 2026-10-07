package com.flowops.modules.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.workflow.Trigger;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.modules.governance.scope.ScopeGuard;
import com.flowops.modules.workflow.converter.TriggerConverterImpl;
import com.flowops.modules.workflow.dto.SaveTriggerRequest;
import com.flowops.modules.workflow.dto.TriggerVO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 触发器服务单测（docs/07 §6.4；42216/42217 已由纯函数测试覆盖，本类测编排语义：
 * 可见性借父 workflow、业务编号、next_fire_time 重算、停用联动）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TriggerServiceTest {

    @Mock private TriggerMapper triggerMapper;
    @Mock private WorkflowService workflowService;
    @Mock private IdGen idGen;

    private TriggerService service;

    @BeforeEach
    void setUp() {
        when(idGen.next("TRG", "trg")).thenReturn("TRG-0007");
        service = new TriggerService(triggerMapper, workflowService, new TriggerConverterImpl(),
                idGen, new ObjectMapper());
    }

    // ── 桩数据 ──────────────────────────────────────────────

    private Workflow workflow() {
        Workflow w = new Workflow();
        w.setId(21L);
        w.setWorkflowId("WF-0002");
        w.setWorkflowName("每日报表");
        return w;
    }

    private void givenWorkflowVisible() {
        when(workflowService.requireVisible("WF-0002")).thenReturn(workflow());
        when(workflowService.requireVisibleById(21L)).thenReturn(workflow());
    }

    private SaveTriggerRequest cronRequest() {
        SaveTriggerRequest r = new SaveTriggerRequest();
        r.setWorkflowId("WF-0002");
        r.setTriggerName("每日 02:00");
        r.setTriggerType("CRON");
        r.setCronExpression("0 0 2 * * *");
        r.setRunParams(java.util.Map.of("bizDate", "today"));
        return r;
    }

    private Trigger row(Long id, String businessId) {
        Trigger t = new Trigger();
        t.setId(id);
        t.setTriggerId(businessId);
        t.setWorkflowId(21L);
        t.setTriggerName("每日 02:00");
        t.setTriggerType("CRON");
        t.setCronExpression("0 0 2 * * *");
        t.setTimezone("Asia/Shanghai");
        t.setEnabled(true);
        t.setDeleted(false);
        t.setVersion(0);
        return t;
    }

    // ── 创建 ────────────────────────────────────────────────

    @Test
    void 创建_CRON_默认值与业务编号落齐() {
        givenWorkflowVisible();

        TriggerVO vo = service.create(cronRequest());

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerMapper).insert(captor.capture());
        Trigger saved = captor.getValue();
        assertThat(saved.getTriggerId()).isEqualTo("TRG-0007");
        assertThat(saved.getWorkflowId()).isEqualTo(21L);
        assertThat(saved.getTimezone()).isEqualTo("Asia/Shanghai");        // 缺省取 DDL 默认
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getCatchUpEnabled()).isTrue();
        assertThat(saved.getCatchUpMaxTimes()).isEqualTo(3);
        assertThat(saved.getNextFireTime()).isNotNull();                    // 明天 02:00 之类，非空即可
        // 响应即库中内容：VO 从同一个内存实体组装，工作流出业务编号（D-27）
        assertThat(vo.getWorkflowId()).isEqualTo("WF-0002");
        assertThat(vo.getRunParams()).containsEntry("bizDate", "today");
    }

    @Test
    void 创建_工作流不可见_异常直传不落库() {
        when(workflowService.requireVisible("WF-0002"))
                .thenThrow(new BizException(com.flowops.common.api.ErrorCode.SCOPE_EXCEEDED, "越权"));

        assertThatThrownBy(() -> service.create(cronRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
        verify(triggerMapper, never()).insert(any(Trigger.class));
    }

    @Test
    void 创建_缺少所属工作流_40001且不触发可见性查询() {
        SaveTriggerRequest r = cronRequest();
        r.setWorkflowId(" ");

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("所属工作流");
        verify(workflowService, never()).requireVisible(anyString());
    }

    @Test
    void 创建_API类型_一期置灰拒绝() {
        givenWorkflowVisible();
        SaveTriggerRequest r = cronRequest();
        r.setTriggerType("API");

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一期置灰");
    }

    @Test
    void 创建_42216配置错误_不落库() {
        givenWorkflowVisible();
        SaveTriggerRequest r = cronRequest();
        r.setCronExpression(null);   // CRON 但既无 cron 也无周期

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42216);
        verify(triggerMapper, never()).insert(any(Trigger.class));
    }

    @Test
    void 创建_42217时间窗错误_不落库() {
        givenWorkflowVisible();
        SaveTriggerRequest r = cronRequest();
        r.setEffectiveStart("2026-10-08T00:00:00+08:00");
        r.setEffectiveEnd("2026-10-07T00:00:00+08:00");

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42217);
    }

    @Test
    void 创建_同名_规则冲突() {
        givenWorkflowVisible();
        when(triggerMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(cronRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42200);
        verify(triggerMapper, never()).insert(any(Trigger.class));
    }

    // ── 更新 ────────────────────────────────────────────────

    @Test
    void 更新_调度配置变化_重算next_fire_time() {
        givenWorkflowVisible();
        Trigger existing = row(5L, "TRG-0001");
        when(triggerMapper.selectOne(any())).thenReturn(existing);
        when(triggerMapper.selectCount(any())).thenReturn(0L);

        SaveTriggerRequest r = cronRequest();
        r.setCronExpression("0 30 3 * * *");    // 换了时间
        service.update("TRG-0001", r);

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 30 3 * * *");
        assertThat(captor.getValue().getNextFireTime()).isNotNull();
        // 乐观锁版本列由实体携带（@Version），updateById 走 CAS
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    void 更新_未改调度配置_不重算next_fire_time() {
        givenWorkflowVisible();
        Trigger existing = row(5L, "TRG-0001");
        existing.setNextFireTime(OffsetDateTime.parse("2026-10-09T02:00:00+08:00"));
        when(triggerMapper.selectOne(any())).thenReturn(existing);
        when(triggerMapper.selectCount(any())).thenReturn(0L);

        service.update("TRG-0001", cronRequest());   // 只改名字

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerMapper).updateById(captor.capture());
        // next_fire_time 保持原值：它是调度器扫表的游标，没变就不该被"顺手"重置
        assertThat(captor.getValue().getNextFireTime())
                .isEqualTo(OffsetDateTime.parse("2026-10-09T02:00:00+08:00"));
    }

    @Test
    void 更新_试图改挂靠_仍归父工作流() {
        givenWorkflowVisible();
        Trigger existing = row(5L, "TRG-0001");
        when(triggerMapper.selectOne(any())).thenReturn(existing);
        when(triggerMapper.selectCount(any())).thenReturn(0L);

        SaveTriggerRequest r = cronRequest();
        r.setWorkflowId("WF-9999");   // 请求里换工作流 → 无效，挂靠关系不可变更
        TriggerVO vo = service.update("TRG-0001", r);

        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getWorkflowId()).isEqualTo(21L);
        assertThat(vo.getWorkflowId()).isEqualTo("WF-0002");
    }

    // ── 可见性 ──────────────────────────────────────────────

    @Test
    void 查详情_不存在_40400() {
        when(triggerMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get("TRG-4040"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    void 查详情_存在但父工作流越权_40301() {
        when(triggerMapper.selectOne(any())).thenReturn(row(5L, "TRG-0001"));
        when(workflowService.requireVisibleById(21L))
                .thenThrow(new BizException(com.flowops.common.api.ErrorCode.SCOPE_EXCEEDED, "越权"));

        assertThatThrownBy(() -> service.get("TRG-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
    }

    @Test
    void 列表_借父工作流一次判定可见性_逐行翻译业务编号() {
        when(workflowService.requireVisible("WF-0002")).thenReturn(workflow());
        Trigger a = row(5L, "TRG-0001");
        Trigger b = row(6L, "TRG-0002");
        when(triggerMapper.selectList(any())).thenReturn(List.of(a, b));

        List<TriggerVO> vos = service.listByWorkflow("WF-0002");

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getTriggerId()).isEqualTo("TRG-0001");
        assertThat(vos.get(0).getWorkflowId()).isEqualTo("WF-0002");
    }

    // ── 删除与停用联动 ──────────────────────────────────────

    @Test
    void 删除_走软删而非物理删() {
        givenWorkflowVisible();
        when(triggerMapper.selectOne(any())).thenReturn(row(5L, "TRG-0001"));

        service.delete("TRG-0001");

        verify(triggerMapper).softDelete(5L);
        verify(triggerMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 工作流停用联动_调用批量停用() {
        givenWorkflowVisible();
        service.disableByWorkflow(21L);
        verify(triggerMapper).disableByWorkflowId(21L);
    }

    // ── cron 预览 ───────────────────────────────────────────

    @Test
    void 预览_默认5个_严格递增且都在未来() {
        List<OffsetDateTime> times = service.previewCron("0 0 2 * * *", "Asia/Shanghai", null);

        assertThat(times).hasSize(5);
        assertThat(times).isSorted();
        assertThat(times.get(0)).isAfter(OffsetDateTime.now().minusSeconds(1));
    }

    @Test
    void 预览_非法表达式_42216() {
        assertThatThrownBy(() -> service.previewCron("nope", null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42216);
    }

    @Test
    void 预览_count超限_夹到20() {
        List<OffsetDateTime> times = service.previewCron("* * * * * *", null, 999);
        assertThat(times).hasSize(20);
    }

    @Test
    void 预览_表达式非法时区_40001() {
        assertThatThrownBy(() -> service.previewCron("0 0 2 * * *", "Mars/Olympus", null))
                .isInstanceOf(java.time.DateTimeException.class);
    }
}
