package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.BackfillDateRow;
import com.flowops.domain.dto.query.BackfillStatusCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 回填批次读模型（docs/06 §12.1/§12.3；SQL 见 XML）。 */
@Mapper
public interface BackfillQueryMapper {

    /**
     * 时间冲突检测（docs/06 §12.3 原文 SQL）：同工作流 + 同业务日期 + 计划投递时刻 ±30 分钟。
     * server 请求线程调用，{@code task} 已登记数据权限 → <b>禁止给 task 起别名</b>。
     *
     * @return 命中的任务业务编号与状态；未命中为空
     */
    BackfillDateRow findConflictTask(@Param("workflowId") long workflowId,
                                     @Param("bizDate") LocalDate bizDate,
                                     @Param("windowStart") OffsetDateTime windowStart,
                                     @Param("windowEnd") OffsetDateTime windowEnd);

    /** 批次内已有任务的日期集合（去重；投放引擎推导"待投放日期"用）。 */
    List<LocalDate> findDispatchedDates(@Param("batchId") long batchRowId);

    /** 批次内任务按状态计数（投放引擎刷新批次进度 + DONE 判定）。 */
    List<BackfillStatusCountRow> countTasksByStatus(@Param("batchId") long batchRowId);

    /** 批次失败的日期队列（重跑失败步骤：并发数并入 waiting 重新投放）。 */
    List<LocalDate> findFailedDates(@Param("batchId") long batchRowId);
}
