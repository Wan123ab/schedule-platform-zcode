package com.flowops.modules.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.domain.dto.query.DashboardFailureRow;
import com.flowops.domain.dto.query.ResourceUsageRow;
import com.flowops.domain.dto.query.TaskStatsRow;
import com.flowops.domain.dto.query.TaskTrendRow;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.ExecutorNodeMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.dashboard.DashboardQueryMapper;
import com.flowops.modules.alert.dto.AlertRecordVO;
import com.flowops.modules.alert.service.AlertRecordService;
import com.flowops.modules.dashboard.dto.ClusterHealthVO;
import com.flowops.modules.dashboard.dto.DashboardSummaryVO;
import com.flowops.modules.dashboard.dto.PlatformHealthVO;
import com.flowops.modules.dashboard.dto.QueueBacklogVO;
import com.flowops.modules.dashboard.dto.RecentFailureVO;
import com.flowops.modules.dashboard.dto.ResourceUsageVO;
import com.flowops.modules.dashboard.dto.TaskTrendVO;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 工作台聚合服务（M4-S5，CONTRACT §11 七端点 + §11.1 平台健康度）。
 *
 * <p><b>聚合口径</b>：task 系统计经 DashboardQueryMapper（DataScope PROJECT 下自动行级过滤）；
 * cluster/queue/节点系读冗余计数列（docs/05 §6.3：定时聚合）；告警系复用 {@link AlertRecordService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 调度器选主锁（与 scheduler LeaderElector.LOCK_KEY 字面量一致，D-08 不能引用其常量） */
    static final String SCHEDULER_LEADER_LOCK_KEY = "flowops:scheduler:leader";
    /** leader 实例标识 key（与 scheduler LeaderElector.INSTANCE_KEY 对应；锁 value 是 Redisson 内部持有者标识，不可用） */
    static final String SCHEDULER_LEADER_INSTANCE_KEY = "flowops:scheduler:leader:instance";

    private final DashboardQueryMapper dashboardQuery;
    private final ClusterMapper clusterMapper;
    private final QueueMapper queueMapper;
    private final ExecutorNodeMapper executorNodeMapper;
    private final AlertRecordService alertRecordService;
    private final StringRedisTemplate redis;
    private final DataSource dataSource;

    // ── CONTRACT §11 七端点 ─────────────────────────────────

    public DashboardSummaryVO summary() {
        TaskStatsRow stats = dashboardQuery.taskStats();
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setTaskTotal(stats.getTaskTotal());
        vo.setRunningCount(stats.getRunningCount());
        long finished = nz(stats.getSuccessCount()) + nz(stats.getFailedCount());
        vo.setSuccessRate(finished == 0 ? null
                : round2((double) nz(stats.getSuccessCount()) / finished));
        vo.setQueueBacklog(sumWaiting());
        vo.setOnlineNodes(countNodesByStatus("ONLINE"));
        vo.setTotalNodes(countNodes());
        vo.setFailedToday(stats.getFailedToday());
        vo.setAlerts(alertRecordService.alertsToday());
        return vo;
    }

    /** 近 N 日趋势（无数据日补零，前端直接画线）。 */
    public List<TaskTrendVO> taskTrend(int days) {
        int window = Math.min(Math.max(1, days), 30);
        Map<LocalDate, TaskTrendRow> byDate = new TreeMap<>();
        for (TaskTrendRow row : dashboardQuery.taskTrend(window)) {
            byDate.put(row.getDate(), row);
        }
        LocalDate today = LocalDate.now();
        List<TaskTrendVO> result = new ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            TaskTrendRow row = byDate.get(date);
            result.add(new TaskTrendVO(date,
                    row == null ? 0 : nz(row.getSubmitted()),
                    row == null ? 0 : nz(row.getSuccess()),
                    row == null ? 0 : nz(row.getFailed())));
        }
        return result;
    }

    public List<ResourceUsageVO> resourceUsage() {
        return dashboardQuery.resourceUsage().stream().map(r -> {
            ResourceUsageVO vo = new ResourceUsageVO();
            vo.setClusterId(r.getClusterId());
            vo.setClusterName(r.getClusterName());
            vo.setCpuUsed(r.getCpuUsed());
            vo.setCpuTotal(r.getCpuTotal());
            vo.setGpuUsed(r.getGpuUsed());
            vo.setGpuTotal(r.getGpuTotal());
            vo.setDiskUsed(r.getDiskUsed());
            vo.setDiskTotal(r.getDiskTotal());
            return vo;
        }).toList();
    }

    public List<ClusterHealthVO> clusterHealth() {
        return clusterMapper.selectList(Wrappers.<Cluster>lambdaQuery()
                        .eq(Cluster::getDeleted, false)
                        .orderByAsc(Cluster::getId))
                .stream().map(c -> {
                    ClusterHealthVO vo = new ClusterHealthVO();
                    vo.setClusterId(c.getClusterId());
                    vo.setClusterName(c.getClusterName());
                    vo.setStatus(c.getStatus());
                    vo.setNodeOnline(c.getNodeOnline());
                    vo.setNodeTotal(c.getNodeTotal());
                    vo.setRunningTasks(c.getRunningTaskCount());
                    return vo;
                }).toList();
    }

    public List<QueueBacklogVO> queueBacklog() {
        return queueMapper.selectList(Wrappers.<Queue>lambdaQuery()
                        .eq(Queue::getDeleted, false)
                        .orderByAsc(Queue::getId))
                .stream().map(q -> {
                    QueueBacklogVO vo = new QueueBacklogVO();
                    vo.setQueueId(q.getQueueId());
                    vo.setQueueName(q.getQueueName());
                    vo.setWaitingCount(q.getWaitingTaskCount());
                    vo.setMaxWaiting(q.getMaxWaitingTasks());
                    return vo;
                }).toList();
    }

    public List<RecentFailureVO> recentFailures() {
        return dashboardQuery.recentFailures(10).stream().map(RecentFailureVO::of).toList();
    }

    public List<AlertRecordVO> latestAlerts() {
        return alertRecordService.latest(10);
    }

    // ── CONTRACT §11.1 平台健康度 ───────────────────────────

    public PlatformHealthVO platformHealth() {
        PlatformHealthVO vo = new PlatformHealthVO();
        vo.setScheduler(schedulerHealth());
        vo.setDb(dbHealth());
        vo.setRedis(redisHealth());
        vo.setNodes(nodesHealth());
        vo.setQueues(queueWaters());
        vo.setToday(todaySection());
        vo.setRecentAlerts(alertRecordService.latest(10).stream().map(a -> {
            PlatformHealthVO.RecentAlert ra = new PlatformHealthVO.RecentAlert();
            ra.setAlertId(a.getAlertId());
            ra.setLevel(a.getLevel());
            ra.setTitle(a.getEventType() + (a.getTargetName() == null ? "" : " · " + a.getTargetName()));
            ra.setFiredAt(a.getFiredAt());
            return ra;
        }).toList());
        return vo;
    }

    /** 选主锁推导（O-71：standby_count/last_tick_at 调度器未上报，返回 null）。 */
    private PlatformHealthVO.Scheduler schedulerHealth() {
        PlatformHealthVO.Scheduler s = new PlatformHealthVO.Scheduler();
        try {
            Boolean alive = redis.hasKey(SCHEDULER_LEADER_LOCK_KEY);
            s.setSchedulerAlive(Boolean.TRUE.equals(alive));
            if (Boolean.TRUE.equals(alive)) {
                // 锁 value 是 Redisson 内部持有者标识，实例 ID 由 LeaderElector 写入的 instance key 提供
                s.setLeaderId(redis.opsForValue().get(SCHEDULER_LEADER_INSTANCE_KEY));
                Long ttl = redis.getExpire(SCHEDULER_LEADER_LOCK_KEY);
                s.setLeaseRemainingSeconds(ttl == null || ttl < 0 ? null : ttl);
            }
        } catch (Exception e) {
            log.warn("调度器选主锁查询失败: {}", e.getMessage());
            s.setSchedulerAlive(false);
        }
        s.setIsLeader(null);
        s.setLastTickAt(null);
        s.setTickIntervalMs(1000);
        s.setStandbyCount(null);
        return s;
    }

    private PlatformHealthVO.Db dbHealth() {
        PlatformHealthVO.Db db = new PlatformHealthVO.Db();
        try {
            HikariPoolMXBean pool = dataSource.unwrap(HikariPoolMXBean.class);
            db.setPoolActive(pool.getActiveConnections());
            db.setPoolIdle(pool.getIdleConnections());
            db.setPoolMax(pool.getTotalConnections() + pool.getIdleConnections());
        } catch (Exception e) {
            log.debug("连接池统计不可得: {}", e.getMessage());
        }
        db.setSlowQueryCount1m(null);   // 无慢查询登记设施（O-71）
        return db;
    }

    private PlatformHealthVO.Redis redisHealth() {
        PlatformHealthVO.Redis r = new PlatformHealthVO.Redis();
        try {
            Properties info = redis.execute((RedisConnection conn) -> conn.serverCommands().info());
            r.setConnected(info != null);
            r.setUsedMemoryHuman(info == null ? null : info.getProperty("used_memory_human"));
            r.setEvictionPolicy(info == null ? null : info.getProperty("maxmemory-policy"));
        } catch (Exception e) {
            r.setConnected(false);
        }
        r.setQueueDepth(sumWaiting());
        return r;
    }

    private PlatformHealthVO.Nodes nodesHealth() {
        PlatformHealthVO.Nodes n = new PlatformHealthVO.Nodes();
        long total = countNodes();
        long online = countNodesByStatus("ONLINE");
        n.setTotal(total);
        n.setOnline(online);
        n.setOffline(total - online);
        n.setOnlineRate(total == 0 ? null : round2((double) online / total));
        return n;
    }

    private List<PlatformHealthVO.QueueWater> queueWaters() {
        return queueMapper.selectList(Wrappers.<Queue>lambdaQuery()
                        .eq(Queue::getDeleted, false).orderByAsc(Queue::getId))
                .stream().map(q -> {
                    PlatformHealthVO.QueueWater w = new PlatformHealthVO.QueueWater();
                    w.setQueueRowId(q.getId());
                    w.setQueueId(q.getQueueId());
                    w.setName(q.getQueueName());
                    w.setWaiting(q.getWaitingTaskCount());
                    w.setMaxConcurrentTasks(q.getMaxConcurrentTasks());
                    w.setMaxWaitingTasks(q.getMaxWaitingTasks());
                    Integer max = q.getMaxWaitingTasks();
                    w.setWaterLevel(max == null || max == 0 ? null
                            : round2(q.getWaitingTaskCount() == null ? 0
                                    : (double) q.getWaitingTaskCount() / max));
                    return w;
                }).toList();
    }

    private PlatformHealthVO.Today todaySection() {
        TaskStatsRow stats = dashboardQuery.taskStats();
        PlatformHealthVO.Today t = new PlatformHealthVO.Today();
        t.setDispatchedCount(stats.getSubmittedToday());
        t.setFailedCount(stats.getFailedToday());
        t.setLogIngestBytes(null);   // 日志字节数无登记设施（O-71）
        return t;
    }

    // ── 小工具 ──────────────────────────────────────────────

    private long sumWaiting() {
        return queueMapper.selectList(Wrappers.<Queue>lambdaQuery()
                        .eq(Queue::getDeleted, false))
                .stream().mapToLong(q -> q.getWaitingTaskCount() == null ? 0 : q.getWaitingTaskCount())
                .sum();
    }

    private long countNodesByStatus(String status) {
        return executorNodeMapper.selectCount(Wrappers.<ExecutorNode>lambdaQuery()
                .eq(ExecutorNode::getOnlineStatus, status)
                .eq(ExecutorNode::getDeleted, false));
    }

    private long countNodes() {
        return executorNodeMapper.selectCount(Wrappers.<ExecutorNode>lambdaQuery()
                .eq(ExecutorNode::getDeleted, false));
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
