package com.flowops.domain.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.task.TaskStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 步骤实例 Mapper（docs/05 §3.5 task_step）。
 * CRUD 走 BaseMapper；调度器的高频条件更新（CAS）在 XML ——
 * SQL 与 Java 分离（docs/10），且 CAS 的 WHERE 条件必须逐字评审。
 */
@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStep> {

    /**
     * 出队两段式 ①：DB 占位（docs/06 §4.3）。
     * WHERE status='WAITING_RESOURCE' AND dispatch_token IS NULL —— 条件即防重复下发的全部语义：
     * 受影响 0 行 = 已被他方处理（调度器另一实例 / 人工干预 / 崩溃恢复残留）。
     *
     * @return 受影响行数（0/1）
     */
    int casClaimForDispatch(@Param("id") Long id, @Param("dispatchToken") String dispatchToken);

    /**
     * 出队两段式 ② 的回滚：ZREM 失败（已被并发移除）时把占位退回。
     * WHERE dispatch_token = #{token} —— 只回滚自己占的位，不碰他人。
     */
    int rollbackClaim(@Param("id") Long id, @Param("dispatchToken") String dispatchToken);

    /**
     * 通用步骤 CAS 转移（docs/03 §3.4 / docs/06 §2.3）：
     * DAG 推进、生命周期收敛等所有状态流转的唯一通道，
     * from 条件由调用方传入（调用前必须经 StepStateTransitions 校验）。
     *
     * @return 受影响行数（0 = 状态已被他方改变，调用方放弃并重读）
     */
    int casTransition(@Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus);

    /**
     * 标记/清除互斥锁持有点（docs/06 §6.3：成功获取写 true，终态收敛清 false）。
     * token 条件保证只有当前占位者能动这个标记。
     */
    int setMutexHolder(@Param("id") Long id,
                       @Param("dispatchToken") String dispatchToken,
                       @Param("mutexGroup") String mutexGroup,
                       @Param("holder") boolean holder);

    /**
     * 步骤重试登记（docs/06 §9.1）：RUNNING → RETRYING，retry_count + 1，
     * next_retry_at 落库（调度器重启后重试不丢——内存定时器方案已被 v3 评审否掉）。
     */
    int markRetrying(@Param("id") Long id,
                     @Param("nextRetryAt") java.time.OffsetDateTime nextRetryAt,
                     @Param("failReason") String failReason);

    /**
     * 重跑失败步骤的单步 reset（docs/06 §9.3 ③，server 人工干预）：清执行痕迹、回 NOT_STARTED；
     * {@code retry_count} 保留（累加语义）。多列 UPDATE 走 XML（docs/10）。
     *
     * @return 受影响行数
     */
    int resetForRerun(@Param("id") Long id);
}
