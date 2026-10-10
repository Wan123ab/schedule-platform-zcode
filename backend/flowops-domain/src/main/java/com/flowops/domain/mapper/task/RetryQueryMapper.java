package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.RetryingStepRow;
import com.flowops.domain.dto.query.TaskStepRetryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 重试调度读模型（docs/06 §9.1 ⑤；SQL 见 XML）。 */
@Mapper
public interface RetryQueryMapper {

    /** 到期重试扫描：RETRYING 且 next_retry_at <= now（命中 idx_tstep_next_retry 部分索引）。 */
    List<RetryingStepRow> findDueRetrying(@Param("limit") int limit);

    /**
     * 批量取重试历史（任务详情用）。
     *
     * <p><b>为什么按 ids 批量而不是单步查询</b>：详情页一次要渲染全部步骤的重试历史，
     * 逐步查询会让 N 个步骤变成 N 次往返（N+1）。这里一次取回后在 Service 里按
     * {@code taskStepId} 分组。</p>
     */
    List<TaskStepRetryRow> findRetryHistoryByTaskStepIds(@Param("ids") List<Long> taskStepIds);

    /** 重试历史（R19：结构化，供任务详情展示与统计；M1 在收敛时写单条）。 */
    int insertRetryRecord(@Param("taskStepId") Long taskStepId,
                          @Param("attemptNo") int attemptNo,
                          @Param("status") String status,
                          @Param("machineIp") String machineIp,
                          @Param("exitCode") Integer exitCode,
                          @Param("failReason") String failReason);
}
