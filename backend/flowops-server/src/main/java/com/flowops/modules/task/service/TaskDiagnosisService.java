package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.dto.query.EtaStatsRow;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.task.DiagnosisQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.modules.task.dto.DiagnosisBlockReasonVO;
import com.flowops.modules.task.dto.DiagnosisEtaVO;
import com.flowops.modules.task.dto.DiagnosisSuggestionVO;
import com.flowops.modules.task.dto.TaskDiagnosisVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调度诊断（M4 S1b，CONTRACT §7 {@code GET /tasks/{taskId}/diagnosis}，docs/06 §5.4/§5.5）。
 *
 * <p><b>只说 DB 与 Redis 能证明的话</b>：本服务与调度器协作走 D-08（DB 状态 + Redis），
 * server 不可达调度器内存里的预留账本，因此——</p>
 * <ul>
 *   <li>排队位置：由 {@code task} 表推导，排序口径与调度器 {@code QueueScore} 一致
 *       （优先级降序 + enqueue_seq 升序），"前方数量"用一次 count 完成；</li>
 *   <li>ETA：docs/06 §5.5 的统计估算 —— 队列近 7 天中位排队时长 ×（位置 ÷ 平均并发度），
 *       结果缓存 Redis {@code flowops:eta:{queueId}}，10 分钟 TTL（cache-aside 等价于
 *       文档的"每 10 分钟刷新"）；样本 &lt; 20 → 「数据不足，无法估算」（docs/00 E-05）；</li>
 *   <li>阻塞原因：互斥等待读步骤状态（{@code WAITING_RESOURCE} ⟺ 等互斥锁，docs/06 §6.1）
 *       + Redis holder/waiters；排队等待读任务状态。NO_MATCHING_NODE / nearMiss 不可达，
 *       登记为 README-M4 O-43，等调度器把匹配失败原因写进 {@code task_step.block_reason}
 *       后由本端点透传——<b>不提前编造"看起来像诊断"的数据</b>。</li>
 * </ul>
 *
 * <p><b>Redis 不可用不致命</b>：ETA 缓存读写、互斥 holder/waiters 读取全部降级
 * （缓存降级为直查 DB，锁信息降级为空），诊断主路径不因缓存抖动 500。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDiagnosisService {

    /** ETA 缓存 key 前缀（docs/06 §5.5：flowops:eta:{queueId}） */
    static final String ETA_CACHE_PREFIX = "flowops:eta:";

    /** ETA 缓存 TTL（docs/06 §5.5：每 10 分钟刷新） */
    static final Duration ETA_CACHE_TTL = Duration.ofMinutes(10);

    /**
     * 互斥锁 Redis key（docs/06 §6.3 线协议，跨模块契约）。
     * ⚠️ 字面量必须与 flowops-scheduler 的 {@code MutexLockManager} 保持一致
     * （D-08：server 不依赖 scheduler 模块，不能引用其常量）——
     * {@code TaskDiagnosisServiceTest} 用字面量断言钉死，漂移即测试红。
     */
    static final String MUTEX_KEY_PREFIX = "flowops:mutex:";
    static final String MUTEX_HOLDER_SUFFIX = ":holder";
    static final String MUTEX_WAITERS_SUFFIX = ":waiters";

    /** ETA 样本下限（docs/06 §5.5 / docs/00 E-05：< 20 显示"数据不足"） */
    static final long MIN_ETA_SAMPLE = 20;

    /** 统计均值口径（docs/06 §5.5 的"平均并发度"取数口径，README-M4 O-45） */
    private static final int DEFAULT_CONCURRENCY = 1;

    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final QueueMapper queueMapper;
    private final DiagnosisQueryMapper diagnosisQueryMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** ETA 缓存条目（JSON 出入 Redis） */
    record EtaCacheEntry(double medianWaitSeconds, long sampleCount) {}

    public TaskDiagnosisVO diagnose(String taskId) {
        Task task = requireTask(taskId);
        TaskDiagnosisVO vo = new TaskDiagnosisVO();
        vo.setTaskId(task.getTaskId());
        vo.setStatus(task.getStatus());

        boolean terminal = task.getStatus() != null && isTerminal(task.getStatus());
        if (terminal) {
            // 终态任务没有"为什么还没跑"的问题：不查队列、不查步骤、不算 ETA
            vo.setBlockingReasons(List.of());
            vo.setSuggestions(List.of());
            return vo;
        }

        Long position = queuePosition(task);
        vo.setQueuePosition(position);
        vo.setAheadCount(position == null ? null : position - 1);
        vo.setEta(buildEta(task, position));
        vo.setBlockingReasons(buildBlockingReasons(task, position));
        vo.setSuggestions(buildSuggestions(vo.getBlockingReasons()));
        return vo;
    }

    // ── 排队位置 ────────────────────────────────────────────

    /**
     * 队列位置（1 起）。排序口径 = 优先级降序 + enqueue_seq 升序，与调度器
     * {@code QueueScore#of} 的出队顺序一致 —— 诊断报的位置必须就是调度器眼里的位置，
     * 两套口径迟早出"诊断说第 1 个、调度器先跑了别人"的事故。
     *
     * <p>仅 PENDING 且 {@code enqueue_seq}/{@code queue_id} 可用时计算；否则 null
     * （RUNNING/SCHEDULING 不在排队；历史脏行缺 seq 时宁可不给数，不给错数）。</p>
     */
    private Long queuePosition(Task task) {
        if (task.getStatus() != TaskStatus.PENDING || task.getQueueId() == null || task.getEnqueueSeq() == null) {
            return null;
        }
        int myPriority = task.getPriority() == null ? 0 : task.getPriority();
        Long ahead = taskMapper.selectCount(Wrappers.<Task>lambdaQuery()
                .eq(Task::getQueueId, task.getQueueId())
                .eq(Task::getStatus, TaskStatus.PENDING)
                .eq(Task::getDeleted, false)
                .and(w -> w
                        .gt(Task::getPriority, myPriority)
                        .or(w2 -> w2.eq(Task::getPriority, myPriority)
                                .lt(Task::getEnqueueSeq, task.getEnqueueSeq()))));
        return ahead + 1;
    }

    // ── ETA（docs/06 §5.5）──────────────────────────────────

    private DiagnosisEtaVO buildEta(Task task, Long position) {
        if (position == null || task.getQueueId() == null) {
            return null;
        }
        EtaCacheEntry stats = loadEtaStats(task.getQueueId());
        if (stats == null || stats.sampleCount() < MIN_ETA_SAMPLE) {
            DiagnosisEtaVO vo = new DiagnosisEtaVO();
            vo.setEstimatedSeconds(null);
            vo.setSampleCount(stats == null ? 0 : stats.sampleCount());
            vo.setBasis("数据不足，无法估算");
            return vo;
        }
        Queue queue = queueMapper.selectById(task.getQueueId());
        int concurrency = queue == null || queue.getMaxConcurrentTasks() == null
                || queue.getMaxConcurrentTasks() <= 0
                ? DEFAULT_CONCURRENCY
                : queue.getMaxConcurrentTasks();
        long estimatedSeconds = Math.round(stats.medianWaitSeconds() * position / concurrency);
        DiagnosisEtaVO vo = new DiagnosisEtaVO();
        vo.setEstimatedSeconds(estimatedSeconds);
        vo.setSampleCount(stats.sampleCount());
        vo.setBasis("基于该队列近 7 天平均排队时长估算");
        return vo;
    }

    /**
     * ETA 统计（cache-aside，TTL 10 分钟）。缓存坏值按未命中处理，直查兜底。
     */
    private EtaCacheEntry loadEtaStats(long queueId) {
        String key = ETA_CACHE_PREFIX + queueId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                EtaCacheEntry entry = objectMapper.readValue(cached, EtaCacheEntry.class);
                if (entry.sampleCount() >= 0) {
                    return entry;
                }
            }
        } catch (Exception e) {
            log.warn("ETA 缓存读取失败，降级直查: key={} err={}", key, e.getMessage());
        }
        EtaStatsRow row = diagnosisQueryMapper.etaStats(queueId);
        double median = row == null || row.getMedianWaitSeconds() == null ? 0 : row.getMedianWaitSeconds();
        long sample = row == null || row.getSampleCount() == null ? 0 : row.getSampleCount();
        EtaCacheEntry entry = new EtaCacheEntry(median, sample);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ETA_CACHE_TTL);
        } catch (Exception e) {
            log.warn("ETA 缓存写入失败（不影响本次结果）: key={} err={}", key, e.getMessage());
        }
        return entry;
    }

    // ── 阻塞原因 ────────────────────────────────────────────

    private List<DiagnosisBlockReasonVO> buildBlockingReasons(Task task, Long position) {
        List<DiagnosisBlockReasonVO> reasons = new ArrayList<>();
        if (position != null) {
            DiagnosisBlockReasonVO reason = new DiagnosisBlockReasonVO();
            reason.setType("CONCURRENCY_WAIT");
            reason.setDesc("等待并发额度释放，前方 " + (position - 1) + " 个任务排队中");
            reasons.add(reason);
        }
        for (TaskStep step : taskStepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, task.getId())
                .eq(TaskStep::getStatus, StepStatus.WAITING_RESOURCE)
                .orderByAsc(TaskStep::getStepIndex))) {
            if (step.getMutexGroup() == null || step.getMutexGroup().isBlank()) {
                continue;   // WAITING_RESOURCE 语义上即等互斥锁（docs/06 §6.1）；无组名的脏行跳过
            }
            reasons.add(mutexReason(step));
        }
        return reasons;
    }

    private DiagnosisBlockReasonVO mutexReason(TaskStep step) {
        String group = step.getMutexGroup();
        DiagnosisBlockReasonVO reason = new DiagnosisBlockReasonVO();
        reason.setType("MUTEX_GROUP");
        reason.setMutexGroup(group);
        reason.setStepInstanceId(step.getStepInstanceId());
        reason.setStepName(step.getStepName());
        reason.setHolderDesc(redisValue(MUTEX_KEY_PREFIX + group + MUTEX_HOLDER_SUFFIX));
        Long waiters = redisLong(MUTEX_KEY_PREFIX + group + MUTEX_WAITERS_SUFFIX);
        reason.setWaitersCount(waiters);
        return reason;
    }

    // ── 建议 ────────────────────────────────────────────────

    /**
     * 文案建议（无动作码 —— README-M4 O-45：docs §5.4 的三个动作码全属
     * NO_MATCHING_NODE，此处不出）。顺序与 {@code blockingReasons} 一致。
     */
    private List<DiagnosisSuggestionVO> buildSuggestions(List<DiagnosisBlockReasonVO> reasons) {
        List<DiagnosisSuggestionVO> suggestions = new ArrayList<>();
        for (DiagnosisBlockReasonVO reason : reasons) {
            DiagnosisSuggestionVO s = new DiagnosisSuggestionVO();
            if ("CONCURRENCY_WAIT".equals(reason.getType())) {
                s.setText("任务在排队中，无需处理；若紧急可联系运维评估插队（需队列允许）");
            } else {
                s.setText("步骤「" + reason.getStepName() + "」等待互斥锁「" + reason.getMutexGroup()
                        + "」释放（当前等待者 " + (reason.getWaitersCount() == null ? 0 : reason.getWaitersCount())
                        + " 个）；互斥组不支持优先级插队");
            }
            suggestions.add(s);
        }
        return suggestions;
    }

    // ── Redis 降级读取 ──────────────────────────────────────

    private String redisValue(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读取失败（holder 降级为空）: key={} err={}", key, e.getMessage());
            return null;
        }
    }

    private Long redisLong(String key) {
        try {
            return redisTemplate.opsForZSet().zCard(key);
        } catch (Exception e) {
            log.warn("Redis 读取失败（waiters 降级为空）: key={} err={}", key, e.getMessage());
            return null;
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCESS || status == TaskStatus.FAILED
                || status == TaskStatus.STOPPED || status == TaskStatus.TIMEOUT
                || status == TaskStatus.PARTIAL;
    }

    private Task requireTask(String taskId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getTaskId, taskId)
                .eq(Task::getDeleted, false));
        if (task == null) {
            // 行级过滤下"越权"与"不存在"在 SQL 上等价（与 TaskQueryService 同一口径）
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId,
                    Map.of("resource_type", "TASK", "resource_id", taskId));
        }
        return task;
    }
}
