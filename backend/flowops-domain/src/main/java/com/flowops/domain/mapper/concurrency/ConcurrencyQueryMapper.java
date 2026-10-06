package com.flowops.domain.mapper.concurrency;

import com.flowops.domain.dto.query.ProjectConcurrencyConfig;
import com.flowops.domain.dto.query.QueueConcurrencyConfig;
import com.flowops.domain.dto.query.WorkflowConcurrencyConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 并发控制读模型查询（docs/06 §6.1 四级并发总表的"配置来源"与判定输入）。
 * 全部为只读投影；SQL 见 XML（resources/mapper/concurrency/ConcurrencyQueryMapper.xml）。
 *
 * <p><b>为何用读模型 DTO 而非整表实体</b>：并发判定每 tick / 每次提交都会读，
 * 只取判定所需列，避免实体演进（加列）悄悄拖宽这条高频查询。</p>
 */
@Mapper
public interface ConcurrencyQueryMapper {

    /** 工作流并发配置（concurrency_policy + max_parallel_runs）。 */
    WorkflowConcurrencyConfig findWorkflowConcurrency(@Param("workflowId") Long workflowId);

    /** 项目并发额度（max_concurrent_tasks）。 */
    ProjectConcurrencyConfig findProjectConcurrency(@Param("projectId") Long projectId);

    /** 工作流在途实例数（SCHEDULING/RUNNING/STOPPING —— STOPPING 仍占用并发额度直至收敛）。 */
    long countRunningTasksByWorkflow(@Param("workflowId") Long workflowId);

    /** 项目在途任务数（同上口径）。 */
    long countRunningTasksByProject(@Param("projectId") Long projectId);

    /** 40901 附带的 running_task_id（业务编号 TASK-xxx）：仅在 FORBID 拒绝时调用（懒查询，不占快乐路径成本）。 */
    String findAnyRunningTaskId(@Param("workflowId") Long workflowId);

    // ── 队列级（出队时检查，docs/06 §6.1 第三行）──────────────────

    /** 队列并发配置（max_concurrent_tasks）。 */
    QueueConcurrencyConfig findQueueConcurrency(@Param("queueId") Long queueId);

    /**
     * 队列当前运行中步骤数（SCHEDULING/RUNNING）。
     * 步骤无队列列，经 task.queue_id 关联 —— freeSlots = max_concurrent − 本查询结果（docs/06 §4.3）。
     */
    long countRunningStepsByQueue(@Param("queueId") Long queueId);
}
