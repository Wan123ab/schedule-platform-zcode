package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.RetryingStepRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 重试调度读模型（docs/06 §9.1 ⑤；SQL 见 XML）。 */
@Mapper
public interface RetryQueryMapper {

    /** 到期重试扫描：RETRYING 且 next_retry_at <= now（命中 idx_tstep_next_retry 部分索引）。 */
    List<RetryingStepRow> findDueRetrying(@Param("limit") int limit);

    /** 重试历史（R19：结构化，供任务详情展示与统计；M1 在收敛时写单条）。 */
    int insertRetryRecord(@Param("taskStepId") Long taskStepId,
                          @Param("attemptNo") int attemptNo,
                          @Param("status") String status,
                          @Param("machineIp") String machineIp,
                          @Param("exitCode") Integer exitCode,
                          @Param("failReason") String failReason);
}
