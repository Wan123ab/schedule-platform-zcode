package com.flowops.domain.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.task.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务表 Mapper（docs/05 §3.5 task）。
 *
 * <p><b>约定（docs/10 / docs/03 §2.1）</b>：CRUD 走 BaseMapper（零 SQL）；
 * 任何自定义 SQL 一律写在 XML（resources/mapper/task/TaskMapper.xml），
 * 接口只留方法签名 —— SQL 与 Java 分离，DBA 可评审、可 grep。</p>
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 活跃任务数（status ∈ PENDING/SCHEDULING/RUNNING/STOPPING 且未删除）。
     * 用途：调度器启动恢复自检（docs/06 §10.2 ⑧）；实现见 XML，
     * 命中部分索引 idx_task_active（部分索引把扫描范围限制在"同时活跃的任务数"内）。
     */
    long countActiveTasks();

    /**
     * 任务状态 CAS 转移（docs/03 §3.4）：from 条件防人工操作与调度器并发覆盖。
     * 调用前必须经 TaskStateTransitions.canTransition 校验。
     *
     * @return 受影响行数（0 = 状态已被他方改变）
     */
    int casStatus(@Param("id") Long id,
                  @Param("fromStatus") String fromStatus,
                  @Param("toStatus") String toStatus);
}
