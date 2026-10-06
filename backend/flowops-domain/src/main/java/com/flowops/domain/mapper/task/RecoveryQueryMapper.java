package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.LedgerRecoveryRow;
import com.flowops.domain.dto.query.MutexHolderRow;
import com.flowops.domain.dto.query.WaitingRecoveryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 启动恢复读模型（docs/06 §10.2：PG 是唯一真源，Redis 只放可重建派生态）；SQL 见 XML。 */
@Mapper
public interface RecoveryQueryMapper {

    /** ② 重建就绪队列：全部 WAITING_RESOURCE 步骤（原 score 组成部分一并取出，保 FIFO）。 */
    List<WaitingRecoveryRow> findWaitingSteps(@Param("limit") int limit);

    /** ③ 重建互斥锁持有点：mutex_holder=true 且 RUNNING（锁 TTL 过期时重新获取）。 */
    List<MutexHolderRow> findMutexHolders(@Param("limit") int limit);

    /**
     * ④ 重建预留账本：SCHEDULING/RUNNING（已分配节点的在途步骤）。
     * 口径说明：docs/06 §10.2 ④ 写"Σ 非终态"，但账本只追踪<b>已分配节点</b>的步骤
     * （WAITING_RESOURCE 尚无 machine_ip，re-match 时重新 acquire）——语义等价，避免重试步骤双重计数。
     */
    List<LedgerRecoveryRow> findLedgerRows(@Param("limit") int limit);
}
