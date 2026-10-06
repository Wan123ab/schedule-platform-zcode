package com.flowops.domain.mapper.concurrency;

import com.flowops.domain.dto.query.ActiveTaskRow;
import com.flowops.domain.dto.query.DispatchableNodeRow;
import com.flowops.domain.dto.query.EdgeRow;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.dto.query.StepRuntimeRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 调度管线读模型（docs/06 §3.2 tick 各阶段的取数；SQL 见 XML）。
 * 全部为窄投影 —— 管线每 tick 跑一次，查询面必须最小化。
 */
@Mapper
public interface SchedulingQueryMapper {

    /** 活跃任务（PENDING 准入 + SCHEDULING/RUNNING 推进；命中 idx_task_active 部分索引）。 */
    List<ActiveTaskRow> findActiveTasks();

    /** 工作流版本内的步骤定义（DagGraph 装配）。 */
    List<StepDefRow> findStepDefsByVersion(@Param("workflowVersionId") Long workflowVersionId);

    /** 工作流版本内的连线（DagGraph 装配）。 */
    List<EdgeRow> findEdgesByVersion(@Param("workflowVersionId") Long workflowVersionId);

    /** 任务内的步骤实例运行时（编排器内存状态初始化）。 */
    List<StepRuntimeRow> findStepRuntimesByTask(@Param("taskId") Long taskId);

    /** 可派发节点（enabled + 在线 + 凭据有效；与 NodeMatcher 的过滤前置对齐，缩小匹配输入）。 */
    List<DispatchableNodeRow> findDispatchableNodes();
}
