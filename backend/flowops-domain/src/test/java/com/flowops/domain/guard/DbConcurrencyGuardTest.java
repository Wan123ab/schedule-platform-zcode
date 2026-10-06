package com.flowops.domain.guard;

import com.flowops.common.guard.CheckResult;
import com.flowops.domain.dto.query.ProjectConcurrencyConfig;
import com.flowops.domain.dto.query.WorkflowConcurrencyConfig;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbConcurrencyGuard 单测（Mockito 隔离 DB）：验证判定链顺序 —— 项目额度先行，工作流策略随后，
 * FORBID 拒绝时才懒查询 running_task_id（快乐路径零额外成本）。
 */
@ExtendWith(MockitoExtension.class)
class DbConcurrencyGuardTest {

    @Mock
    private ConcurrencyQueryMapper mapper;

    @InjectMocks
    private DbConcurrencyGuard guard;

    private void givenProject(long running, int limit) {
        ProjectConcurrencyConfig config = new ProjectConcurrencyConfig();
        config.setProjectId(1L);
        config.setMaxConcurrentTasks(limit);
        when(mapper.findProjectConcurrency(1L)).thenReturn(config);
        when(mapper.countRunningTasksByProject(1L)).thenReturn(running);
    }

    private void givenWorkflow(String policy, int maxParallel, long running) {
        WorkflowConcurrencyConfig config = new WorkflowConcurrencyConfig();
        config.setWorkflowId(10L);
        config.setConcurrencyPolicy(policy);
        config.setMaxParallelRuns(maxParallel);
        when(mapper.findWorkflowConcurrency(10L)).thenReturn(config);
        when(mapper.countRunningTasksByWorkflow(10L)).thenReturn(running);
    }

    @Test
    void 项目额度满_直接DEFER_不查工作流() {
        givenProject(5, 5);

        CheckResult r = guard.checkBeforeSubmit(10L, 1L);

        assertThat(r.action()).isEqualTo(CheckResult.Action.DEFER_PROJECT_QUOTA);
        verify(mapper, never()).findWorkflowConcurrency(10L);   // 判定链短路：省钱且语义清晰
    }

    @Test
    void 项目有余量_FORBID且有实例_拒绝并附带runningTaskId() {
        givenProject(1, 5);
        givenWorkflow("FORBID", 1, 1);
        when(mapper.findAnyRunningTaskId(10L)).thenReturn("TASK-20261006-0001");

        CheckResult r = guard.checkBeforeSubmit(10L, 1L);

        assertThat(r.action()).isEqualTo(CheckResult.Action.REJECT_FORBID);
        assertThat(r.runningTaskId()).isEqualTo("TASK-20261006-0001");   // 40901 附带字段
    }

    @Test
    void 项目有余量_QUEUE且有空位_放行_不触发懒查询() {
        givenProject(0, 5);
        givenWorkflow("QUEUE", 3, 2);

        CheckResult r = guard.checkBeforeSubmit(10L, 1L);

        assertThat(r.allowed()).isTrue();
        verify(mapper, never()).findAnyRunningTaskId(10L);   // 快乐路径不付懒查询成本
    }

    @Test
    void 项目有余量_QUEUE且满_DEFER排队() {
        givenProject(2, 5);
        givenWorkflow("QUEUE", 2, 2);

        CheckResult r = guard.checkBeforeSubmit(10L, 1L);

        assertThat(r.deferred()).isTrue();
        assertThat(r.action()).isEqualTo(CheckResult.Action.DEFER_QUEUE);
    }
}
