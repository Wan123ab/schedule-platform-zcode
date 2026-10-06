package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.StuckDispatchRow;
import com.flowops.domain.dto.query.UnreachableStepRow;
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

    // ── 心跳离线判定（docs/06 §8.1，HeartbeatScanner）──────────

    /** ①：15~45s 未心跳的在线节点 miss+1（G5：状态不变）。 */
    int incrementMissCount();

    /** ②：>45s 未心跳 → OFFLINE（G4；告警事件随 M4 告警引擎）。 */
    int markOfflineNodes();

    /** ③：失联节点（>5min 恢复窗口）上的 RUNNING 步骤（§8.1 ⑤）。 */
    List<UnreachableStepRow> findUnreachableSteps(@Param("windowSeconds") long windowSeconds,
                                                   @Param("limit") int limit);

    /** ③ 收敛：RUNNING → FAILED（fail_reason_code=NODE_UNREACHABLE）。 */
    int casStepFailed(@Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("failReasonCode") String failReasonCode);
}
