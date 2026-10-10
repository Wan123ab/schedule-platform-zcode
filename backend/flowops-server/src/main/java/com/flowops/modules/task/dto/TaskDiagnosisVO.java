package com.flowops.modules.task.dto;

import com.flowops.common.enums.TaskStatus;
import lombok.Data;

import java.util.List;

/**
 * 调度诊断（CONTRACT §7 {@code GET /tasks/{taskId}/diagnosis}）。
 *
 * <p><b>字段口径</b>：{@code queuePosition}/{@code aheadCount} 仅在任务 {@code PENDING}
 * 且 {@code enqueue_seq} 可用时非空；终态任务三者为 {@code null}/{@code 空}。</p>
 *
 * <p><b>刻意没有的字段（README-M4 O-43，docs/06 §5.4 的 NO_MATCHING_NODE 结构）</b>：
 * {@code constraints[]}/{@code nearMiss[]} 依赖调度器内存预留账本
 * （{@code available = total − reserved}），D-08 下 server 不可达，调度器目前也未写
 * {@code task_step.block_reason} —— 等调度器把匹配失败原因落库后由本端点透传，不提前造空壳字段。</p>
 */
@Data
public class TaskDiagnosisVO {

    /** 业务编号 TASK-yyyyMMdd-####（D-27） */
    private String taskId;

    private TaskStatus status;

    /** 队列位置（1 起；排序口径 = 优先级降序 + enqueue_seq 升序，与调度器 QueueScore 一致） */
    private Long queuePosition;

    /** 前方排队任务数 = queuePosition − 1 */
    private Long aheadCount;

    /** ETA 估算（docs/06 §5.5）；不在排队中时为 null */
    private DiagnosisEtaVO eta;

    private List<DiagnosisBlockReasonVO> blockingReasons;

    private List<DiagnosisSuggestionVO> suggestions;
}
