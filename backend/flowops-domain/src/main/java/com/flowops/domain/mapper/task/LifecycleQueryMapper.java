package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.StuckDispatchRow;
import com.flowops.domain.dto.query.TimeoutStepRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 生命周期扫描读模型（docs/06 §3.2 阶段⑧；SQL 见 XML）。 */
@Mapper
public interface LifecycleQueryMapper {

    /** SCHEDULING 卡住：占位超过 N 分钟未收到回执（§4.6 下发回执丢失）。 */
    List<StuckDispatchRow> findStuckScheduling(@Param("minutes") int minutes, @Param("limit") int limit);

    /** RUNNING 超时：start_time + timeout_seconds 已过（§8.3 步骤超时）。 */
    List<TimeoutStepRow> findTimedOutSteps(@Param("limit") int limit);
}
