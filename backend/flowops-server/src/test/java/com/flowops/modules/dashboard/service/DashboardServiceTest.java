package com.flowops.modules.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flowops.domain.dto.query.DashboardFailureRow;
import com.flowops.domain.dto.query.ResourceUsageRow;
import com.flowops.domain.dto.query.TaskStatsRow;
import com.flowops.domain.dto.query.TaskTrendRow;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.ExecutorNodeMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.dashboard.DashboardQueryMapper;
import com.flowops.modules.alert.service.AlertRecordService;
import com.flowops.modules.dashboard.dto.DashboardSummaryVO;
import com.flowops.modules.dashboard.dto.PlatformHealthVO;
import com.flowops.modules.dashboard.dto.TaskTrendVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 工作台聚合单测（M4-S5）。
 *
 * <p><b>本测试守的是什么</b>——工作台是用户打开的第一屏，数字错=信任崩：</p>
 * <ol>
 *   <li><b>成功率口径</b>：SUCCESS / 终态总数（PARTIAL 不算成功也不算失败），无终态 → null
 *       而不是 0 或 100%（0 任务时显示"成功率 0%"会吓到用户）；</li>
 *   <li><b>趋势补零且日期升序</b>：无数据日必须补零（断线 vs 零量是天壤之别），顺序由
 *       TreeMap 保证（禁止顺序随机的 ImmutableCollections —— 集合顺序血泪）；</li>
 *   <li><b>调度器锁 key 字面量</b>：与 scheduler LeaderElector.LOCK_KEY 一致
 *       （D-08 不能引用其常量，字面量漂移 = 健康度永远"失联"）；</li>
 *   <li><b>平台健康度降级</b>：Redis/DB 池统计任何异常都不抛，可得字段如实为 null。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock private DashboardQueryMapper dashboardQuery;
    @Mock private ClusterMapper clusterMapper;
    @Mock private QueueMapper queueMapper;
    @Mock private ExecutorNodeMapper executorNodeMapper;
    @Mock private AlertRecordService alertRecordService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private DataSource dataSource;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(dashboardQuery, clusterMapper, queueMapper,
                executorNodeMapper, alertRecordService, redis, dataSource);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.hasKey(any(String.class))).thenReturn(false);
        lenient().when(alertRecordService.alertsToday()).thenReturn(0L);
        TaskStatsRow stats = new TaskStatsRow();
        stats.setTaskTotal(0L);
        stats.setRunningCount(0L);
        stats.setSuccessCount(0L);
        stats.setFailedCount(0L);
        stats.setFailedToday(0L);
        stats.setSubmittedToday(0L);
        lenient().when(dashboardQuery.taskStats()).thenReturn(stats);
        lenient().when(queueMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        lenient().when(executorNodeMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        lenient().when(clusterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    // ── summary ─────────────────────────────────────────────

    @Test
    void summary_successRateIsSuccessOverFinishedTerminals() {
        TaskStatsRow stats = new TaskStatsRow();
        stats.setTaskTotal(100L);
        stats.setRunningCount(4L);
        stats.setSuccessCount(70L);
        stats.setFailedCount(30L);          // FAILED+TIMEOUT 累计
        stats.setFailedToday(2L);
        stats.setSubmittedToday(10L);
        when(dashboardQuery.taskStats()).thenReturn(stats);
        Queue q = new Queue();
        q.setWaitingTaskCount(7);
        when(queueMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q));
        when(executorNodeMapper.selectCount(any(Wrapper.class))).thenReturn(12L);
        when(alertRecordService.alertsToday()).thenReturn(3L);

        DashboardSummaryVO vo = service.summary();
        assertThat(vo.getTaskTotal()).isEqualTo(100);
        assertThat(vo.getRunningCount()).isEqualTo(4);
        assertThat(vo.getSuccessRate()).isEqualTo(0.7);       // 70/(70+30)
        assertThat(vo.getQueueBacklog()).isEqualTo(7);
        assertThat(vo.getOnlineNodes()).isEqualTo(12);
        assertThat(vo.getTotalNodes()).isEqualTo(12);
        assertThat(vo.getFailedToday()).isEqualTo(2);
        assertThat(vo.getAlerts()).isEqualTo(3);
    }

    @Test
    void summary_noTerminalTasks_successRateIsNullNotZero() {
        DashboardSummaryVO vo = service.summary();   // 全 0
        assertThat(vo.getSuccessRate()).isNull();
    }

    // ── task-trend ──────────────────────────────────────────

    @Test
    void taskTrend_fillsZeroDaysInAscendingOrder() {
        LocalDate today = LocalDate.now();
        TaskTrendRow row = new TaskTrendRow();
        row.setDate(today.minusDays(2));
        row.setSubmitted(5L);
        row.setSuccess(3L);
        row.setFailed(1L);
        when(dashboardQuery.taskTrend(7)).thenReturn(List.of(row));   // 只有中间一天有数据

        List<TaskTrendVO> trend = service.taskTrend(7);
        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).getDate()).isEqualTo(today.minusDays(6));   // 升序
        assertThat(trend.get(6).getDate()).isEqualTo(today);
        // 中间那天有数，其余补零
        assertThat(trend.get(4).getSubmitted()).isEqualTo(5);
        assertThat(trend.get(0).getSubmitted()).isZero();
        assertThat(trend.get(6).getSubmitted()).isZero();
    }

    @Test
    void taskTrend_capsDaysTo30() {
        when(dashboardQuery.taskTrend(30)).thenReturn(List.of());
        assertThat(service.taskTrend(999)).hasSize(30);
    }

    // ── cluster-health / resource-usage ─────────────────────

    @Test
    void clusterHealth_readsRedundantCountColumns() {
        Cluster c = new Cluster();
        c.setId(1L);
        c.setClusterId("CL-0001");
        c.setClusterName("主集群");
        c.setStatus("ENABLED");
        c.setNodeOnline(8);
        c.setNodeTotal(10);
        c.setRunningTaskCount(3);
        when(clusterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c));

        var health = service.clusterHealth();
        assertThat(health).hasSize(1);
        assertThat(health.get(0).getClusterId()).isEqualTo("CL-0001");
        assertThat(health.get(0).getNodeOnline()).isEqualTo(8);
        assertThat(health.get(0).getRunningTasks()).isEqualTo(3);
    }

    @Test
    void resourceUsage_translatesAggregatedRow() {
        ResourceUsageRow row = new ResourceUsageRow();
        row.setClusterId("CL-0001");
        row.setClusterName("主集群");
        row.setCpuUsed(new BigDecimal("12.5"));
        row.setCpuTotal(new BigDecimal("32"));
        row.setGpuUsed(BigDecimal.ZERO);
        row.setGpuTotal(new BigDecimal("4"));
        row.setDiskUsed(100L);
        row.setDiskTotal(1000L);
        when(dashboardQuery.resourceUsage()).thenReturn(List.of(row));

        var usage = service.resourceUsage();
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).getCpuUsed()).isEqualByComparingTo("12.5");
        assertThat(usage.get(0).getDiskTotal()).isEqualTo(1000L);
    }

    // ── /platform/health ────────────────────────────────────

    @Test
    void platformHealth_schedulerLockKey_isPinned() {
        // 字面量防漂移：与 scheduler LeaderElector.LOCK_KEY / INSTANCE_KEY 一致（D-08 不能引用其常量）
        assertThat(DashboardService.SCHEDULER_LEADER_LOCK_KEY).isEqualTo("flowops:scheduler:leader");
        assertThat(DashboardService.SCHEDULER_LEADER_INSTANCE_KEY).isEqualTo("flowops:scheduler:leader:instance");
    }

    @Test
    void platformHealth_lockAlive_reportsLeaderAndLease() {
        when(redis.hasKey("flowops:scheduler:leader")).thenReturn(true);
        // leaderId 来自 LeaderElector 写入的实例标识 key（锁 value 是 Redisson 内部持有者标识，不可用）
        when(valueOps.get("flowops:scheduler:leader:instance")).thenReturn("sched-1a2b3c4d");
        when(redis.getExpire("flowops:scheduler:leader")).thenReturn(22L);

        PlatformHealthVO vo = service.platformHealth();
        assertThat(vo.getScheduler().getSchedulerAlive()).isTrue();
        assertThat(vo.getScheduler().getLeaderId()).isEqualTo("sched-1a2b3c4d");
        assertThat(vo.getScheduler().getLeaseRemainingSeconds()).isEqualTo(22);
        assertThat(vo.getScheduler().getIsLeader()).isNull();          // server 视角不可得（O-71）
        assertThat(vo.getScheduler().getStandbyCount()).isNull();
    }

    @Test
    void platformHealth_lockAbsent_reportsNotAlive() {
        when(redis.hasKey("flowops:scheduler:leader")).thenReturn(false);
        PlatformHealthVO vo = service.platformHealth();
        assertThat(vo.getScheduler().getSchedulerAlive()).isFalse();
        assertThat(vo.getScheduler().getLeaderId()).isNull();
    }

    @Test
    void platformHealth_redisDown_neverThrows() {
        when(redis.hasKey(any(String.class))).thenThrow(new RuntimeException("redis down"));
        when(dashboardQuery.resourceUsage()).thenReturn(List.<ResourceUsageRow>of());
        when(dashboardQuery.recentFailures(10)).thenReturn(List.<DashboardFailureRow>of());

        PlatformHealthVO vo = service.platformHealth();
        assertThat(vo.getScheduler().getSchedulerAlive()).isFalse();
        assertThat(vo.getRedis().getConnected()).isFalse();
        // 其余段照常返回
        assertThat(vo.getNodes()).isNotNull();
        assertThat(vo.getToday()).isNotNull();
    }

    @Test
    void platformHealth_nodeCountsDerived() {
        when(executorNodeMapper.selectCount(any(Wrapper.class)))
                .thenAnswer(inv -> {
                    // 两次调用分别是 total 与 ONLINE —— 用顺序桩简化
                    return 10L;
                });
        ExecutorNode online = new ExecutorNode();
        when(executorNodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        PlatformHealthVO vo = service.platformHealth();
        // 桩返回 10 → online/total 均 10 → rate 1.0
        assertThat(vo.getNodes().getOnlineRate()).isEqualTo(1.0);
        assertThat(vo.getNodes().getOffline()).isZero();
    }
}
