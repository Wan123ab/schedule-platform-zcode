package com.flowops.modules.task.service;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.mapper.workflow.WorkflowEdgeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.modules.task.dto.RerunFailedRequest;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.flowops.modules.task.dto.TaskDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务人工干预单测（M4 S2）。
 *
 * <p><b>本测试守的是什么</b>——干预操作全是高危动作，错一处就是生产事故：</p>
 * <ol>
 *   <li><b>停止只写 STOPPING 中间态</b>（docs/06 §15.1：进程还在跑，绝不直接写 STOPPED），
 *       且 CAS 落空时按"状态已变化"拒绝而不是静默成功；</li>
 *   <li><b>整任务重跑 = 新建实例且绑定原冻结版本</b>（D-11：重跑"当时执行的是什么"，
 *       不是当前发布版）；走 {@code TaskSubmitService.create}（D-12 唯一入口）；</li>
 *   <li><b>rerun-failed 的下游闭包</b>：失败步骤的下游 SUCCESS 必须重跑（拿到的是坏数据），
 *       非目标 SUCCESS 保留 output_vars 复用旧值；多列 reset 走 XML
 *       {@code resetForRerun}/{@code resumeAfterRerun}（docs/10：SQL 与 Java 分离）；</li>
 *   <li><b>插队的三道闸门顺序</b>：仅 PENDING → 无互斥组步骤 → 队列允许（PRD §12.2-6）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskInterventionServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private TaskStepMapper taskStepMapper;
    @Mock private WorkflowEdgeMapper workflowEdgeMapper;
    @Mock private QueueMapper queueMapper;
    @Mock private TaskSubmitService submitService;
    @Mock private TaskQueryService taskQueryService;

    private TaskInterventionService service;

    @BeforeEach
    void setUp() {
        service = new TaskInterventionService(taskMapper, taskStepMapper, workflowEdgeMapper,
                queueMapper, submitService, taskQueryService, new ObjectMapper());
    }

    // ── 夹具 ────────────────────────────────────────────────

    private Task task(TaskStatus status) {
        Task t = new Task();
        t.setId(100L);
        t.setTaskId("TASK-20261011-0001");
        t.setWorkflowId(10L);
        t.setWorkflowVersionId(11L);
        t.setProjectId(20L);
        t.setQueueId(40L);
        t.setStatus(status);
        t.setPriority(5);
        t.setBizDate(LocalDate.of(2026, 10, 10));
        t.setVariableSnapshot("{\"paramA\":\"v1\"}");
        return t;
    }

    private TaskStep step(long rowId, String instanceId, long stepId, StepStatus status, String mutexGroup) {
        TaskStep s = new TaskStep();
        s.setId(rowId);
        s.setStepInstanceId(instanceId);
        s.setTaskId(100L);
        s.setStepId(stepId);
        s.setStepName("step-" + stepId);
        s.setStepIndex((int) stepId);
        s.setStatus(status);
        s.setMutexGroup(mutexGroup);
        return s;
    }

    private WorkflowEdge edge(long from, long to) {
        WorkflowEdge e = new WorkflowEdge();
        e.setWorkflowVersionId(11L);
        e.setSourceStepId(from);
        e.setTargetStepId(to);
        return e;
    }

    private Queue jumpQueue(boolean allowed) {
        Queue q = new Queue();
        q.setAllowJumpQueue(allowed);
        return q;
    }

    // ── 停止 ────────────────────────────────────────────────

    @Test
    void stop_reasonTooShort_isRejected() {
        assertThatThrownBy(() -> service.stop("TASK-1", "abc"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOP_REASON_TOO_SHORT);
        verify(taskMapper, never()).selectOne(any());
    }

    @Test
    void stop_nonRunning_isRejected() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.PENDING));
        assertThatThrownBy(() -> service.stop("TASK-20261011-0001", "运维窗口维护"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_STOPPABLE);
        verify(taskMapper, never()).requestStop(any(), anyString(), anyString());
    }

    @Test
    void stop_writesStoppingIntermediateState_notTerminal() {
        // docs/06 §15.1 ①：server 只到 STOPPING；STOPPED 由调度器在全部步骤终结后收敛
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.RUNNING));
        when(taskMapper.requestStop(100L, "system", "运维窗口维护")).thenReturn(1);
        TaskDetailVO detail = new TaskDetailVO();
        when(taskQueryService.get("TASK-20261011-0001")).thenReturn(detail);

        TaskDetailVO result = service.stop("TASK-20261011-0001", "运维窗口维护");

        assertThat(result).isSameAs(detail);
        verify(taskMapper).requestStop(100L, "system", "运维窗口维护");
        verify(taskMapper, never()).casStatus(any(), anyString(), anyString());
    }

    @Test
    void stop_casLost_isRejectedNotSilentlyIgnored() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.RUNNING));
        when(taskMapper.requestStop(any(), anyString(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.stop("TASK-20261011-0001", "运维窗口维护"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_STOPPABLE);
    }

    // ── 整任务重跑 ──────────────────────────────────────────

    @Test
    void retry_nonTerminal_isRejected() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.RUNNING));
        assertThatThrownBy(() -> service.retry("TASK-20261011-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FINISHED);
        verify(submitService, never()).create(any());
    }

    @Test
    void retry_successTask_isRejected() {
        // SUCCESS 不在重跑来源（docs/06 §2.1 铁律：终态不可回退，成功的任务重跑无意义）
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.SUCCESS));
        assertThatThrownBy(() -> service.retry("TASK-20261011-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FINISHED);
    }

    @Test
    void retry_createsNewInstance_boundToOriginalFrozenVersion() {
        Task failed = task(TaskStatus.FAILED);
        when(taskMapper.selectOne(any())).thenReturn(failed);
        TaskDetailVO newDetail = new TaskDetailVO();
        when(taskQueryService.get("TASK-NEW")).thenReturn(newDetail);
        ArgumentCaptor<SubmitTaskRequest> captor = ArgumentCaptor.forClass(SubmitTaskRequest.class);
        when(submitService.create(captor.capture()))
                .thenReturn(new TaskSubmitService.Creation(200L, "TASK-NEW", false, 0));

        TaskDetailVO result = service.retry("TASK-20261011-0001");

        assertThat(result).isSameAs(newDetail);
        SubmitTaskRequest sent = captor.getValue();
        // D-11：重跑绑定"当时执行的是什么"（原冻结版本），不是当前发布版
        assertThat(sent.getVersionId()).isEqualTo(11L);
        assertThat(sent.getWorkflowId()).isEqualTo(10L);
        assertThat(sent.getProjectId()).isEqualTo(20L);
        assertThat(sent.getTargetQueueId()).isEqualTo(40L);
        assertThat(sent.getPriority()).isEqualTo(5);
        assertThat(sent.getBizDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        // 参数 = 原任务变量快照（解析链第 4 层的落库形态）
        assertThat(sent.getRunParams()).containsEntry("paramA", "v1");
    }

    // ── 重跑失败步骤 ────────────────────────────────────────

    @Test
    void rerunFailed_stoppedTask_isRejected() {
        // TaskStateTransitions.RERUN_SOURCES = FAILED/TIMEOUT/PARTIAL；STOPPED 走整任务重跑
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.STOPPED));
        assertThatThrownBy(() -> service.rerunFailed("TASK-20261011-0001", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FINISHED);
    }

    @Test
    void rerunFailed_defaultTargets_includeDownstreamClosure() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.FAILED));
        // s1 失败 → s3 是它的下游；s2 成功且不在下游 → 保留结果复用 output_vars（§9.3 的设计收益）
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.FAILED, null),
                step(2L, "SI-2", 2L, StepStatus.SUCCESS, null),
                step(3L, "SI-3", 3L, StepStatus.NOT_STARTED, null)));
        when(workflowEdgeMapper.selectList(any())).thenReturn(List.of(edge(1L, 3L)));
        when(taskStepMapper.resetForRerun(any())).thenReturn(1);
        when(taskMapper.resumeAfterRerun(100L, "FAILED", 1)).thenReturn(1);

        service.rerunFailed("TASK-20261011-0001", null);

        // 恰好 reset s1 与 s3 两个实例行（按 task_step.id 判定，不误伤 s2）
        verify(taskStepMapper).resetForRerun(1L);
        verify(taskStepMapper).resetForRerun(3L);
        verify(taskStepMapper, never()).resetForRerun(2L);
        // 任务 CAS 回 RUNNING：from = FAILED（防与调度器并发覆盖）；finished = 非目标的 SUCCESS 数
        verify(taskMapper).resumeAfterRerun(100L, "FAILED", 1);
        verify(taskQueryService).get("TASK-20261011-0001");
    }

    @Test
    void rerunFailed_explicitInstances_areValidatedAgainstTask() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.FAILED));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.FAILED, null),
                step(2L, "SI-2", 2L, StepStatus.SUCCESS, null)));
        when(workflowEdgeMapper.selectList(any())).thenReturn(List.of());

        // 不属于本任务的实例号 → NOT_FOUND（而不是静默忽略）
        RerunFailedRequest bad = new RerunFailedRequest();
        bad.setStepInstanceIds(List.of("SI-999"));
        assertThatThrownBy(() -> service.rerunFailed("TASK-20261011-0001", bad))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        // 显式指定 SUCCESS 步骤：作为显式目标也允许重跑（§9.3 ③）
        RerunFailedRequest ok = new RerunFailedRequest();
        ok.setStepInstanceIds(List.of("SI-2"));
        when(taskStepMapper.resetForRerun(2L)).thenReturn(1);
        when(taskMapper.resumeAfterRerun(100L, "FAILED", 0)).thenReturn(1);
        service.rerunFailed("TASK-20261011-0001", ok);
        verify(taskStepMapper, times(1)).resetForRerun(2L);
        verify(taskStepMapper, never()).resetForRerun(1L);
    }

    @Test
    void rerunFailed_withoutFailedSteps_isRejected() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.PARTIAL));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.SUCCESS, null)));
        when(workflowEdgeMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.rerunFailed("TASK-20261011-0001", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
    }

    @Test
    void rerunFailed_taskCasLost_isRejected() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.FAILED));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.FAILED, null)));
        when(workflowEdgeMapper.selectList(any())).thenReturn(List.of());
        when(taskStepMapper.resetForRerun(any())).thenReturn(1);
        when(taskMapper.resumeAfterRerun(100L, "FAILED", 0)).thenReturn(0);

        assertThatThrownBy(() -> service.rerunFailed("TASK-20261011-0001", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATUS_CONFLICT);
    }

    // ── 插队 ────────────────────────────────────────────────

    @Test
    void enqueueFront_nonPending_isRejected() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.RUNNING));
        assertThatThrownBy(() -> service.enqueueFront("TASK-20261011-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TASK_NOT_WAITING);
    }

    @Test
    void enqueueFront_mutexTask_isRejectedWithContractRule() {
        // docs/07 §6.6：42200 + rule=MUTEX_GROUP_NO_PRIORITY —— 锁等待按 FIFO，插队破坏串行语义
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.PENDING));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.NOT_STARTED, "db-migration")));

        assertThatThrownBy(() -> service.enqueueFront("TASK-20261011-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getPayload())
                .isEqualTo(Map.of("rule", "MUTEX_GROUP_NO_PRIORITY"));
        verify(queueMapper, never()).selectById(40L);   // 互斥闸门在队列闸门之前
    }

    @Test
    void enqueueFront_queueForbidden_isRejected() {
        // PRD §12.2-6：队列 allow_jump_queue=false 时禁止插队
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.PENDING));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.NOT_STARTED, null)));
        when(queueMapper.selectById(40L)).thenReturn(jumpQueue(false));

        assertThatThrownBy(() -> service.enqueueFront("TASK-20261011-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getPayload())
                .isEqualTo(Map.of("rule", "QUEUE_JUMP_FORBIDDEN"));
        verify(taskMapper, never()).jumpQueue(100L);
    }

    @Test
    void enqueueFront_updatesPriorityViaTypedCas() {
        when(taskMapper.selectOne(any())).thenReturn(task(TaskStatus.PENDING));
        when(taskStepMapper.selectList(any())).thenReturn(List.of(
                step(1L, "SI-1", 1L, StepStatus.NOT_STARTED, null)));
        when(queueMapper.selectById(40L)).thenReturn(jumpQueue(true));
        when(taskMapper.jumpQueue(100L)).thenReturn(1);
        TaskDetailVO detail = new TaskDetailVO();
        when(taskQueryService.get("TASK-20261011-0001")).thenReturn(detail);

        TaskDetailVO result = service.enqueueFront("TASK-20261011-0001");

        assertThat(result).isSameAs(detail);
        // E-07 上限 100：XML 里的 priority 字面量与本常量互为镜像（防一侧单改）
        assertThat(TaskInterventionService.JUMP_PRIORITY).isEqualTo(100);
        verify(taskMapper).jumpQueue(100L);
    }
}
