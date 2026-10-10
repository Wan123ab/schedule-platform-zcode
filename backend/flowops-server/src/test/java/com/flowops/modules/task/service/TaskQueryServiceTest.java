package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.enums.TriggerType;
import com.flowops.common.exception.BizException;
import com.flowops.domain.dto.query.TaskLogRow;
import com.flowops.domain.dto.query.TaskStepRetryRow;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.entity.workflow.Trigger;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.task.RetryQueryMapper;
import com.flowops.domain.mapper.task.TaskLogMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.modules.task.converter.TaskConverterImpl;
import com.flowops.modules.task.dto.TaskDetailVO;
import com.flowops.modules.task.dto.TaskLogVO;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务读侧单测（M4 S1：列表 / 详情 / 步骤 / 日志）。
 *
 * <p><b>本测试守的是什么</b>：不是"能查出数据"（那靠集成测试），而是三条容易静默走错的契约：</p>
 * <ol>
 *   <li><b>外键只出业务编号</b>（D-27）——断言 {@code workflowId="WF-0001"} 而不是 {@code "10"}；</li>
 *   <li><b>jsonb 出网必须是嵌套对象</b>——断言 {@code variableSnapshot} 是 {@code Map} 而不是
 *       被引号包裹的字符串；坏 JSON 给 {@code null} 而不是抛 500；</li>
 *   <li><b>空集合不查库</b>——没有步骤/没有列表行时，绝不去查翻译表（避免每页 5 次无谓查询）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskQueryServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private TaskStepMapper taskStepMapper;
    @Mock private TaskLogMapper taskLogMapper;
    @Mock private RetryQueryMapper retryQueryMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ClusterMapper clusterMapper;
    @Mock private QueueMapper queueMapper;
    @Mock private TriggerMapper triggerMapper;
    @Mock private OperatorVersionMapper operatorVersionMapper;

    private TaskQueryService service;

    @BeforeEach
    void setUp() {
        service = new TaskQueryService(taskMapper, taskStepMapper, taskLogMapper, retryQueryMapper,
                new TaskConverterImpl(), new ObjectMapper(),
                workflowMapper, projectMapper, clusterMapper, queueMapper, triggerMapper,
                operatorVersionMapper);
    }

    // ── 夹具 ────────────────────────────────────────────────

    private Task task() {
        Task t = new Task();
        t.setId(100L);
        t.setTaskId("TASK-20261009-0001");
        t.setWorkflowId(10L);
        t.setWorkflowName("每日对账");
        t.setWorkflowVersion("WFV-0001-01");
        t.setProjectId(20L);
        t.setClusterId(30L);
        t.setQueueId(40L);
        t.setTriggerId(50L);
        t.setTriggerType(TriggerType.MANUAL);
        t.setStatus(TaskStatus.RUNNING);
        t.setBizDate(LocalDate.of(2026, 10, 9));
        t.setSubmitter("alice");
        t.setPriority(5);
        t.setClusterName("集群A");
        t.setQueueName("默认队列");
        t.setStepTotal(3);
        t.setFinishedSteps(1);
        t.setSubmitAt(OffsetDateTime.parse("2026-10-09T10:00:00+08:00"));
        t.setVariableSnapshot("{\"paramA\":\"v1\"}");
        t.setDiagnosisInfo("{\"queuePosition\":3}");
        return t;
    }

    private Workflow workflow() {
        Workflow w = new Workflow();
        w.setId(10L);
        w.setWorkflowId("WF-0001");
        return w;
    }

    private void stubPage(List<Task> records) {
        Page<Task> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        doReturn(page).when(taskMapper).selectPage(any(), any());
    }

    private void stubDetailRefs() {
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow()));
        Project p = new Project();
        p.setId(20L);
        p.setProjectId("PRJ-0001");
        when(projectMapper.selectById(any())).thenReturn(p);
        Cluster c = new Cluster();
        c.setId(30L);
        c.setClusterId("CL-0001");
        when(clusterMapper.selectById(any())).thenReturn(c);
        Queue q = new Queue();
        q.setId(40L);
        q.setQueueId("Q-0001");
        when(queueMapper.selectById(any())).thenReturn(q);
        Trigger tr = new Trigger();
        tr.setId(50L);
        tr.setTriggerId("TRG-0001");
        when(triggerMapper.selectById(any())).thenReturn(tr);
    }

    private TaskStep step(long id, String instanceId, int index) {
        TaskStep s = new TaskStep();
        s.setId(id);
        s.setStepInstanceId(instanceId);
        s.setStepName("步骤" + index);
        s.setStepIndex(index);
        s.setStatus(com.flowops.common.enums.StepStatus.FAILED);
        s.setOperatorVersionId(900L + index);
        s.setFailReasonDetail("{\"param\":\"timeout\"}");
        s.setResourceRequest("{\"cpu\":4}");
        s.setOutputVars("{}");
        return s;
    }

    // ── 列表 ────────────────────────────────────────────────

    @Test
    void 列表_翻译工作流业务编号_不暴露内部主键() {
        stubPage(List.of(task()));
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow()));

        IPage<TaskVO> result = service.page(1, 20, "RUNNING", null, null, null, null, null, null, null);

        assertThat(result.getRecords()).hasSize(1);
        TaskVO vo = result.getRecords().get(0);
        assertThat(vo.getWorkflowId()).isEqualTo("WF-0001");
        assertThat(vo.getTaskId()).isEqualTo("TASK-20261009-0001");
        assertThat(vo.getStatus()).isEqualTo("RUNNING");
        assertThat(vo.getTriggerType()).isEqualTo("MANUAL");
        assertThat(vo.getClusterName()).isEqualTo("集群A");
    }

    @Test
    void 列表_空结果_不查翻译表() {
        stubPage(List.of());

        IPage<TaskVO> result = service.page(1, 20, null, null, null, null, null, null, null, null);

        assertThat(result.getRecords()).isEmpty();
        verify(workflowMapper, never()).selectList(any());
    }

    @Test
    void 列表_按业务编号过滤_先解析内部主键() {
        stubPage(List.of());
        when(workflowMapper.selectOne(any())).thenReturn(workflow());
        Project p = new Project();
        p.setId(20L);
        p.setProjectId("PRJ-0001");
        when(projectMapper.selectOne(any())).thenReturn(p);
        Cluster c = new Cluster();
        c.setId(30L);
        c.setClusterId("CL-0001");
        when(clusterMapper.selectOne(any())).thenReturn(c);

        service.page(1, 20, null, "WF-0001", "PRJ-0001", "CL-0001", "CRON", null, null, "对账");

        verify(workflowMapper).selectOne(any());
        verify(projectMapper).selectOne(any());
        verify(clusterMapper).selectOne(any());
    }

    @Test
    void 列表_业务日期起晚于止_返回40001() {
        assertThatThrownBy(() -> service.page(1, 20, null, null, null, null, null,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40001));
    }

    @Test
    void 列表_非法状态_返回40001并带允许值() {
        assertThatThrownBy(() -> service.page(1, 20, "NOT_A_STATUS", null, null, null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40001));
    }

    @Test
    void 列表_非法触发类型_返回40001() {
        assertThatThrownBy(() -> service.page(1, 20, null, null, null, null, "NOPE", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40001));
    }

    @Test
    void 列表_工作流编号不存在_返回40400() {
        when(workflowMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.page(1, 20, null, "WF-9999", null, null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    @Test
    void 列表_项目编号不存在_返回40400() {
        when(projectMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.page(1, 20, null, null, "PRJ-9999", null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    @Test
    void 列表_集群编号不存在_返回40400() {
        when(clusterMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.page(1, 20, null, null, null, "CL-9999", null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    // ── 详情 ────────────────────────────────────────────────

    @Test
    void 详情_五个外键全翻译成业务编号_Jsonb解析为嵌套对象() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        stubDetailRefs();

        TaskDetailVO vo = service.get("TASK-20261009-0001");

        assertThat(vo.getWorkflowId()).isEqualTo("WF-0001");
        assertThat(vo.getProjectId()).isEqualTo("PRJ-0001");
        assertThat(vo.getClusterId()).isEqualTo("CL-0001");
        assertThat(vo.getQueueId()).isEqualTo("Q-0001");
        assertThat(vo.getTriggerId()).isEqualTo("TRG-0001");
        // jsonb 必须是嵌套对象，而不是带引号的字符串
        assertThat(vo.getVariableSnapshot()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = (Map<String, Object>) vo.getVariableSnapshot();
        assertThat(snap).containsEntry("paramA", "v1");
        assertThat(vo.getDiagnosisInfo()).isInstanceOf(Map.class);
    }

    @Test
    void 详情_任务不存在_返回40400() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.get("TASK-NOPE"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    @Test
    void 详情_坏Jsonb_按null出网而不是500() {
        Task t = task();
        t.setVariableSnapshot("{这不是合法 JSON");
        t.setDiagnosisInfo("");
        when(taskMapper.selectOne(any())).thenReturn(t);
        stubDetailRefs();

        TaskDetailVO vo = service.get("TASK-20261009-0001");

        assertThat(vo.getVariableSnapshot()).isNull();
        assertThat(vo.getDiagnosisInfo()).isNull();
    }

    @Test
    void 详情_外键为空_不查被引用表() {
        Task t = task();
        t.setWorkflowId(null);
        t.setProjectId(null);
        t.setClusterId(null);
        t.setQueueId(null);
        t.setTriggerId(null);
        when(taskMapper.selectOne(any())).thenReturn(t);

        TaskDetailVO vo = service.get("TASK-20261009-0001");

        assertThat(vo.getWorkflowId()).isNull();
        assertThat(vo.getProjectId()).isNull();
        verify(projectMapper, never()).selectById(any());
        verify(clusterMapper, never()).selectById(any());
    }

    // ── 步骤 ────────────────────────────────────────────────

    @Test
    void 步骤_挂上重试历史与算子版本编号_按步骤分组() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        when(taskStepMapper.selectList(any())).thenReturn(List.of(step(1000L, "SI-1", 0), step(1001L, "SI-2", 1)));

        TaskStepRetryRow r1 = new TaskStepRetryRow();
        r1.setTaskStepId(1000L);
        r1.setAttemptNo(1);
        r1.setStatus("FAILED");
        TaskStepRetryRow r2 = new TaskStepRetryRow();
        r2.setTaskStepId(1000L);
        r2.setAttemptNo(2);
        r2.setStatus("SUCCESS");
        when(retryQueryMapper.findRetryHistoryByTaskStepIds(anyList())).thenReturn(List.of(r1, r2));

        OperatorVersion v0 = new OperatorVersion();
        v0.setId(900L);
        v0.setVersionId("OPV-0001-01");
        OperatorVersion v1 = new OperatorVersion();
        v1.setId(901L);
        v1.setVersionId("OPV-0002-03");
        when(operatorVersionMapper.selectList(any())).thenReturn(List.of(v0, v1));

        List<TaskStepVO> steps = service.steps("TASK-20261009-0001");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getRetryHistory()).hasSize(2);
        assertThat(steps.get(0).getRetryHistory().get(0).getAttemptNo()).isEqualTo(1);
        assertThat(steps.get(1).getRetryHistory()).isEmpty();
        assertThat(steps.get(0).getOperatorVersionId()).isEqualTo("OPV-0001-01");
        assertThat(steps.get(1).getOperatorVersionId()).isEqualTo("OPV-0002-03");
        assertThat(steps.get(0).getResourceRequest()).isInstanceOf(Map.class);
        assertThat(steps.get(0).getFailReasonDetail()).isInstanceOf(Map.class);
    }

    @Test
    void 步骤_无步骤_不查重试历史与算子版本() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        when(taskStepMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.steps("TASK-20261009-0001")).isEmpty();
        verify(retryQueryMapper, never()).findRetryHistoryByTaskStepIds(anyList());
        verify(operatorVersionMapper, never()).selectList(any());
    }

    @Test
    void 步骤_任务不存在_返回40400() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.steps("TASK-NOPE"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    // ── 日志 ────────────────────────────────────────────────

    private void stubStepAndLogs(long total, List<TaskLogRow> rows) {
        when(taskMapper.selectOne(any())).thenReturn(task());
        TaskStep s = step(1000L, "SI-1", 0);
        when(taskStepMapper.selectOne(any())).thenReturn(s);
        when(taskLogMapper.countByTaskStepId(anyLong())).thenReturn(total);
        when(taskLogMapper.selectRange(anyLong(), anyLong(), anyInt())).thenReturn(rows);
    }

    @Test
    void 日志_按行偏移拼接内容_eof在不足一页时为真() {
        TaskLogRow a = new TaskLogRow();
        a.setSeq(1L);
        a.setContent("line-1");
        TaskLogRow b = new TaskLogRow();
        b.setSeq(2L);
        b.setContent("line-2");
        stubStepAndLogs(2L, List.of(a, b));

        TaskLogVO vo = service.logs("TASK-20261009-0001", "SI-1", 0, 1000);

        assertThat(vo.getContent()).isEqualTo("line-1" + System.lineSeparator() + "line-2");
        assertThat(vo.isEof()).isTrue();
        assertThat(vo.getTotalLines()).isEqualTo(2L);
        assertThat(vo.getOffset()).isZero();
        assertThat(vo.getLimit()).isEqualTo(1000);
    }

    @Test
    void 日志_负偏移归零_limit截到上限() {
        stubStepAndLogs(1L, List.of());

        TaskLogVO vo = service.logs("TASK-20261009-0001", "SI-1", -5, 999_999);

        assertThat(vo.getOffset()).isZero();
        assertThat(vo.getLimit()).isEqualTo(5000);
        assertThat(vo.isEof()).isTrue();
        verify(taskLogMapper).selectRange(eq(1000L), eq(0L), eq(5000));
    }

    @Test
    void 日志_无日志行_不查内容() {
        stubStepAndLogs(0L, List.of());

        TaskLogVO vo = service.logs("TASK-20261009-0001", "SI-1", 0, 100);

        assertThat(vo.getContent()).isEmpty();
        assertThat(vo.getTotalLines()).isZero();
        verify(taskLogMapper, never()).selectRange(anyLong(), anyLong(), anyInt());
    }

    @Test
    void 日志_步骤实例不存在_返回40400() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        when(taskStepMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.logs("TASK-20261009-0001", "SI-999", 0, 100))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }
}
