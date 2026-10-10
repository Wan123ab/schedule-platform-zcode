package com.flowops.domain.service;

import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.enums.TriggerType;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.mapper.concurrency.SchedulingQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 任务创建的唯一落库入口（docs/06 §6.2 ①："任何触发方式（手动/定时/API/回填）都必须走这里"）。
 *
 * <p><b>为什么在 domain 而不是 server</b>：server 与 scheduler 互不依赖（D-08），但
 * 手动提交（server）、回填投放（scheduler）走的必须是<b>同一份</b>建任务 + 建步骤逻辑 ——
 * 放 server 则 scheduler 够不着，放 scheduler 则 server 够不着；两者共同的依赖终点是
 * domain。本类只做"落库"这一件事：并发检查（G6 唯一入口在 ConcurrencyGuard）与
 * 队列/版本解析仍由各触发路径的调用方完成。</p>
 *
 * <p><b>发号</b>：业务编号与 enqueue_seq 都经 {@link IdGen}/{@code flowops:seq:enqueue}，
 * server（StringRedisTemplate）与 scheduler（Redisson）操作同一 Redis key 空间，
 * 序号全平台单调（M-09）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCreationService {

    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final SchedulingQueryMapper schedulingQuery;
    private final IdGen idGen;

    /** 建任务入参（调用方已完成并发检查/版本解析/队列解析）。 */
    public record Spec(Long workflowId, String workflowName, Long workflowVersionId, String workflowVersion,
                       Long projectId, TriggerType triggerType, Long backfillBatchId,
                       java.time.LocalDate bizDate, String submitter,
                       Long queueId, Integer priority, String variableSnapshotJson) {}

    /** 建任务结果。 */
    public record Creation(long taskRowId, String taskId) {}

    /**
     * 建任务实例（PENDING）+ 全部步骤实例（NOT_STARTED）。
     * 准入推进（PENDING → SCHEDULING）由 scheduler 的 QuotaScanner 统一执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public Creation create(Spec spec) {
        String taskId = idGen.nextDated("TASK", "task");
        Task task = new Task();
        task.setTaskId(taskId);
        task.setWorkflowId(spec.workflowId());
        task.setWorkflowName(spec.workflowName());
        task.setWorkflowVersionId(spec.workflowVersionId());
        task.setWorkflowVersion(spec.workflowVersion());
        task.setProjectId(spec.projectId());
        task.setTriggerType(spec.triggerType());
        task.setBackfillBatchId(spec.backfillBatchId());
        task.setBizDate(spec.bizDate());
        task.setSubmitter(spec.submitter());
        task.setQueueId(spec.queueId());
        task.setPriority(spec.priority() == null ? 0 : spec.priority());
        task.setStatus(TaskStatus.PENDING);
        task.setEnqueueSeq(nextEnqueueSeq());
        task.setVariableSnapshot(spec.variableSnapshotJson() == null ? "{}" : spec.variableSnapshotJson());
        task.setSubmitAt(java.time.OffsetDateTime.now());
        taskMapper.insert(task);

        // 步骤实例（实例数 = 版本步骤数，docs/05 R18；enqueue_seq 全局发号，M-09）
        List<StepDefRow> defs = schedulingQuery.findStepDefsByVersion(spec.workflowVersionId()).stream()
                .filter(d -> !"NOTE".equals(d.getStepType()))
                .sorted(Comparator.comparing(StepDefRow::getStepId))
                .toList();
        if (defs.isEmpty()) {
            throw new com.flowops.common.exception.BizException(
                    com.flowops.common.api.ErrorCode.RULE_NOT_SATISFIED,
                    "工作流版本没有可执行步骤（DAG 校验规则 1）");
        }
        int index = 0;
        for (StepDefRow def : defs) {
            TaskStep step = new TaskStep();
            step.setStepInstanceId("SI-" + nextEnqueueSeq());   // M1 口径：seq 充当实例编号
            step.setTaskId(task.getId());
            step.setStepId(def.getStepId());
            step.setStepName(def.getStepName());
            step.setStepIndex(index++);
            step.setStatus(StepStatus.NOT_STARTED);
            step.setEnqueueSeq(nextEnqueueSeq());
            step.setRetryCount(0);
            step.setMutexGroup(def.getMutexGroup());   // 派发期互斥与恢复期等待者重建都依赖实例行携带组名
            taskStepMapper.insert(step);
        }

        log.info("任务已创建 task={} workflow={} version={} steps={} trigger={} backfill={}",
                taskId, spec.workflowId(), spec.workflowVersion(), defs.size(),
                spec.triggerType(), spec.backfillBatchId());
        return new Creation(task.getId(), taskId);
    }

    /** 全局入队序号（M-09：Redis INCR 严格单调，同毫秒并列会破坏 FIFO 确定性）。 */
    private long nextEnqueueSeq() {
        Long seq = idGen.incrementRaw("enqueue");
        if (seq == null) {
            throw new com.flowops.common.exception.BizException(
                    com.flowops.common.api.ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 不可用，无法发号");
        }
        return seq;
    }
}
