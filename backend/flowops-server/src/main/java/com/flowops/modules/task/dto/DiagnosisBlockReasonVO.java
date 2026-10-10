package com.flowops.modules.task.dto;

import lombok.Data;

/**
 * 阻塞原因（docs/07 §6.6 的 MUTEX_GROUP 形态 + 排队等待）。
 *
 * <p><b>type 取值（README-M4 O-46）</b>：</p>
 * <ul>
 *   <li>{@code MUTEX_GROUP} —— docs/07 §6.6 定义；步骤在 {@code WAITING_RESOURCE}
 *       且带 {@code mutex_group}（docs/06 §6.1：拿不到互斥锁的步骤进此状态）。</li>
 *   <li>{@code CONCURRENCY_WAIT} —— docs 只定义了「PENDING + queue_position」语义
 *       （docs/07 §6.4 v3 修订），未给类型名；排队可能源于项目/工作流/队列任一级
 *       并发闸门（docs/06 §6.1），故用中性名，不自造更细的分型。</li>
 * </ul>
 */
@Data
public class DiagnosisBlockReasonVO {

    private String type;

    /** 人读说明（前端直接展示） */
    private String desc;

    /** 仅 MUTEX_GROUP：等待中的步骤实例（业务编号 SI-xxxx） */
    private String stepInstanceId;

    /** 仅 MUTEX_GROUP：步骤名 */
    private String stepName;

    /** 仅 MUTEX_GROUP：互斥组名 */
    private String mutexGroup;

    /**
     * 仅 MUTEX_GROUP：持锁者描述（Redis holder 值，形如 "task:TASK-x/step:数据同步"）。
     * <p>⚠️ 结构化字段（holder_task_id / holding_since / holding_seconds，docs/07 §6.6）
     * 暂不可得：holder value 只存这个描述串（docs/06 §6.3 / MutexLockManager），
     * 改 JSON 结构属调度器切片，登记为 README-M4 O-44。</p>
     */
    private String holderDesc;

    /** 仅 MUTEX_GROUP：等待者数量（Redis waiters ZSet 基数，docs/06 §6.3.1） */
    private Long waitersCount;
}
