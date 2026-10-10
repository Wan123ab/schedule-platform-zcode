package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.exception.BizException;
import com.flowops.domain.dto.query.EtaStatsRow;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.task.DiagnosisQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.modules.task.dto.DiagnosisBlockReasonVO;
import com.flowops.modules.task.dto.TaskDiagnosisVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度诊断单测（M4 S1b）。
 *
 * <p><b>本测试守的是什么</b>——四条容易静默走错的契约：</p>
 * <ol>
 *   <li><b>排队位置口径 = 调度器出队口径</b>（QueueScore：优先级降序 + enqueue_seq 升序）。
 *       两套口径迟早出"诊断说下一个、调度器先跑了别人"的事故 —— 用 wrapper 的 SQL 片段断言钉死；</li>
 *   <li><b>互斥锁 Redis key 与调度器字面量一致</b>（D-08：不能引用 scheduler 模块，只能靠
 *       测试钉字面量防漂移，docs/06 §6.3 是真源）；</li>
 *   <li><b>ETA 样本闸门与缓存语义</b>：样本 &lt; 20 → "数据不足"；缓存命中不查库；
 *       坏值/Redis 故障一律降级，诊断不能因缓存抖动 500；</li>
 *   <li><b>终态任务零查询</b>：没有"为什么还没跑"的问题，就不该为它打四张表。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskDiagnosisServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private TaskStepMapper taskStepMapper;
    @Mock private QueueMapper queueMapper;
    @Mock private DiagnosisQueryMapper diagnosisQueryMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zSetOps;

    private TaskDiagnosisService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存（纯单测无 MyBatis 容器，手工装一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskDiagnosisService(taskMapper, taskStepMapper, queueMapper,
                diagnosisQueryMapper, redisTemplate, new ObjectMapper());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    // ── 夹具 ────────────────────────────────────────────────

    private Task pendingTask() {
        Task t = new Task();
        t.setId(100L);
        t.setTaskId("TASK-20261011-0001");
        t.setStatus(TaskStatus.PENDING);
        t.setQueueId(40L);
        t.setEnqueueSeq(105L);
        t.setPriority(5);
        return t;
    }

    private TaskStep waitingStep() {
        TaskStep s = new TaskStep();
        s.setId(7L);
        s.setTaskId(100L);
        s.setStepInstanceId("SI-0007");
        s.setStepName("数据同步");
        s.setStatus(StepStatus.WAITING_RESOURCE);
        s.setMutexGroup("db-migration");
        s.setStepIndex(2);
        return s;
    }

    private EtaStatsRow stats(double medianSeconds, long sample) {
        EtaStatsRow row = new EtaStatsRow();
        row.setMedianWaitSeconds(medianSeconds);
        row.setSampleCount(sample);
        return row;
    }

    // ── 排队位置（口径必须与 QueueScore 一致）────────────────

    @Test
    void pending_task_reportsPositionAndAhead() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(4L);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getQueuePosition()).isEqualTo(5L);
        assertThat(vo.getAheadCount()).isEqualTo(4L);
    }

    @Test
    void position_countQuery_followsSchedulerScoreOrdering() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(0L);

        service.diagnose("TASK-20261011-0001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectCount(captor.capture());
        LambdaQueryWrapper<Task> wrapper = (LambdaQueryWrapper<Task>) captor.getValue();

        // 前方 = 同队列 PENDING 中：优先级更高，或同优先级但 seq 更小（QueueScore 出队序）
        assertThat(wrapper.getSqlSegment())
                .contains("queue_id =")
                .contains("status =")
                .contains("deleted =")
                .contains("priority >")
                .contains("enqueue_seq <");
        assertThat(wrapper.getParamNameValuePairs())
                .containsValue(40L)                     // queueId
                .containsValue(TaskStatus.PENDING)      // 排队状态
                .containsValue(5)                       // 我的优先级（同优先级分支）
                .containsValue(105L);                   // 我的 seq（FIFO 分支）
    }

    @Test
    void pending_withoutEnqueueSeq_givesNoNumbersRatherThanWrongNumbers() {
        Task task = pendingTask();
        task.setEnqueueSeq(null);
        when(taskMapper.selectOne(any())).thenReturn(task);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getQueuePosition()).isNull();
        assertThat(vo.getAheadCount()).isNull();
        assertThat(vo.getEta()).isNull();
        verify(taskMapper, never()).selectCount(any());
    }

    // ── 终态 / 非排队态 ─────────────────────────────────────

    @Test
    void terminal_task_isZeroQuery() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.SUCCESS);
        when(taskMapper.selectOne(any())).thenReturn(task);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getQueuePosition()).isNull();
        assertThat(vo.getAheadCount()).isNull();
        assertThat(vo.getEta()).isNull();
        assertThat(vo.getBlockingReasons()).isEmpty();
        assertThat(vo.getSuggestions()).isEmpty();
        verify(taskMapper, never()).selectCount(any());
        verify(taskStepMapper, never()).selectList(any());
        verify(diagnosisQueryMapper, never()).etaStats(anyLong());
    }

    @Test
    void running_task_hasNoPosition_butStillChecksMutexWaits() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.RUNNING);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskStepMapper.selectList(any())).thenReturn(List.of());

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getQueuePosition()).isNull();
        assertThat(vo.getEta()).isNull();
        verify(taskStepMapper).selectList(any());
    }

    @Test
    void unknown_task_throwsNotFound() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.diagnose("TASK-NOPE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TASK-NOPE");
    }

    // ── 互斥阻塞（Redis key 字面量钉死）──────────────────────

    @Test
    void mutexKeys_arePinnedToSchedulerContract() {
        // D-08：server 不能依赖 scheduler 模块，MutexLockManager 的 key 格式只能靠这里钉住。
        // 真源 = docs/06 §6.3（flowops:mutex:{group}:holder / :waiters）。
        // 若 scheduler 侧改 key 而本测试没红，说明两边漂移了 —— 先改测试再改代码。
        assertThat(TaskDiagnosisService.MUTEX_KEY_PREFIX).isEqualTo("flowops:mutex:");
        assertThat(TaskDiagnosisService.MUTEX_HOLDER_SUFFIX).isEqualTo(":holder");
        assertThat(TaskDiagnosisService.MUTEX_WAITERS_SUFFIX).isEqualTo(":waiters");
        assertThat(TaskDiagnosisService.ETA_CACHE_PREFIX).isEqualTo("flowops:eta:");
        assertThat(TaskDiagnosisService.ETA_CACHE_TTL).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void waitingResource_step_reportsMutexGroupWithHolderAndWaiters() {
        Task task = pendingTask();
        // RUNNING：互斥等待发生在出队之后（docs/06 §6.1），排队逻辑不在本用例范围
        task.setStatus(TaskStatus.RUNNING);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskStepMapper.selectList(any())).thenReturn(List.of(waitingStep()));
        when(valueOps.get("flowops:mutex:db-migration:holder"))
                .thenReturn("task:TASK-0009/step:数据同步");
        when(zSetOps.zCard("flowops:mutex:db-migration:waiters")).thenReturn(3L);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getBlockingReasons()).hasSize(1);
        DiagnosisBlockReasonVO reason = vo.getBlockingReasons().get(0);
        assertThat(reason.getType()).isEqualTo("MUTEX_GROUP");
        assertThat(reason.getMutexGroup()).isEqualTo("db-migration");
        assertThat(reason.getStepInstanceId()).isEqualTo("SI-0007");
        assertThat(reason.getStepName()).isEqualTo("数据同步");
        assertThat(reason.getHolderDesc()).isEqualTo("task:TASK-0009/step:数据同步");
        assertThat(reason.getWaitersCount()).isEqualTo(3L);
        // 建议：有阻塞就有建议，且互斥组明确不支持插队
        assertThat(vo.getSuggestions()).hasSize(1);
        assertThat(vo.getSuggestions().get(0).getText()).contains("不支持优先级插队");
    }

    @Test
    void waitingResource_withoutMutexGroup_isSkipped() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.RUNNING);
        when(taskMapper.selectOne(any())).thenReturn(task);
        TaskStep dirty = waitingStep();
        dirty.setMutexGroup(null);
        when(taskStepMapper.selectList(any())).thenReturn(List.of(dirty));

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getBlockingReasons()).isEmpty();
        assertThat(vo.getSuggestions()).isEmpty();
        verify(valueOps, never()).get(anyString());
    }

    // ── ETA（docs/06 §5.5）──────────────────────────────────

    @Test
    void eta_belowSampleThreshold_showsInsufficientData() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(valueOps.get("flowops:eta:40")).thenReturn(null);
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(120.0, 12L));

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getEta()).isNotNull();
        assertThat(vo.getEta().getEstimatedSeconds()).isNull();
        assertThat(vo.getEta().getSampleCount()).isEqualTo(12L);
        assertThat(vo.getEta().getBasis()).isEqualTo("数据不足，无法估算");
    }

    @Test
    void eta_formula_medianTimesPositionDividedByConcurrency() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(2L);   // 位置 3
        when(valueOps.get("flowops:eta:40")).thenReturn(null);
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(600.0, 50L));
        Queue queue = new Queue();
        queue.setMaxConcurrentTasks(2);
        when(queueMapper.selectById(40L)).thenReturn(queue);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        // 600s × 3 ÷ 2 = 900s（docs/06 §5.5 公式原样）
        assertThat(vo.getEta().getEstimatedSeconds()).isEqualTo(900L);
        assertThat(vo.getEta().getBasis()).isEqualTo("基于该队列近 7 天平均排队时长估算");
    }

    @Test
    void eta_concurrencyUnlimited_fallsBackToSerial() {
        // README-M4 O-45：max_concurrent_tasks 为 NULL（不限）时按 1（串行）计
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(4L);   // 位置 5
        when(valueOps.get("flowops:eta:40")).thenReturn(null);
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(100.0, 30L));
        Queue queue = new Queue();
        queue.setMaxConcurrentTasks(null);
        when(queueMapper.selectById(40L)).thenReturn(queue);

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getEta().getEstimatedSeconds()).isEqualTo(500L);
    }

    @Test
    void eta_cacheHit_skipsDatabase() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(valueOps.get("flowops:eta:40"))
                .thenReturn("{\"medianWaitSeconds\":300.0,\"sampleCount\":40}");

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getEta().getEstimatedSeconds()).isEqualTo(300L);   // 位置 1 → ÷并发 前 ×1
        verify(diagnosisQueryMapper, never()).etaStats(anyLong());
        // 命中缓存不应回写
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void eta_cacheMiss_writesBackWithTenMinuteTtl() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(valueOps.get("flowops:eta:40")).thenReturn(null);
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(60.0, 25L));

        service.diagnose("TASK-20261011-0001");

        verify(valueOps).set(eq("flowops:eta:40"), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void eta_cacheCorrupted_fallsBackToDatabase() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(valueOps.get("flowops:eta:40")).thenReturn("not-a-json{");
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(60.0, 25L));

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getEta().getEstimatedSeconds()).isEqualTo(60L);
    }

    @Test
    void eta_redisBroken_degradesToDatabaseWithout500() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(valueOps.get("flowops:eta:40")).thenThrow(new IllegalStateException("redis down"));
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(60.0, 25L));

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getEta().getEstimatedSeconds()).isEqualTo(60L);
    }

    // ── 排队建议 ────────────────────────────────────────────

    @Test
    void queued_task_getsConcurrencyWaitReasonAndSuggestion() {
        Task task = pendingTask();
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(6L);
        when(valueOps.get("flowops:eta:40")).thenReturn(null);
        when(diagnosisQueryMapper.etaStats(40L)).thenReturn(stats(60.0, 25L));

        TaskDiagnosisVO vo = service.diagnose("TASK-20261011-0001");

        assertThat(vo.getBlockingReasons()).hasSize(1);
        assertThat(vo.getBlockingReasons().get(0).getType()).isEqualTo("CONCURRENCY_WAIT");
        assertThat(vo.getBlockingReasons().get(0).getDesc()).contains("6");
        assertThat(vo.getSuggestions()).hasSize(1);
        assertThat(vo.getSuggestions().get(0).getText()).contains("插队");
    }
}
