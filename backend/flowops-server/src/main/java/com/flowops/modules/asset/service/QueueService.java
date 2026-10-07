package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.modules.asset.converter.QueueConverter;
import com.flowops.modules.asset.dto.QueueVO;
import com.flowops.modules.asset.dto.SaveQueueRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 队列服务（docs/05 §3.3 queue）。队列是<b>集群内</b>的并发闸门单元，故：
 *
 * <ul>
 *   <li>队列的可见性天然继承自所属集群 —— 行级过滤注入的是 {@code queue.cluster_id IN (被授权集群)}，
 *       与集群维度共用同一份 {@code ScopeContext}，无需在业务层再判一次；</li>
 *   <li>创建/改归属时必须先 {@link ClusterService#requireVisible}（对目标集群有数据范围，
 *       否则等于把队列"挂"到自己看不到的机器上）；</li>
 *   <li>禁用队列前检查运行中任务：队列是并发闸门，禁用而不检查会让在跑的步骤失去归属上下文。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final QueueMapper queueMapper;
    private final ClusterService clusterService;
    private final StringRedisTemplate redis;
    private final QueueConverter converter;
    private final ScopeGuard scopeGuard;

    // ── 查询 ────────────────────────────────────────────────

    /** 某集群下的队列分页（docs/07 §5.4 集群详情页的队列 tab）。 */
    public IPage<QueueVO> pageByCluster(String clusterId, long page, long size, String status) {
        Cluster cluster = clusterService.requireVisible(clusterId);
        Page<Queue> result = queueMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Queue>lambdaQuery()
                        .eq(Queue::getDeleted, false)
                        .eq(Queue::getClusterId, cluster.getId())
                        .eq(status != null && !status.isBlank(), Queue::getStatus, status)
                        .orderByAsc(Queue::getQueueId));
        return result.convert(queue -> toVO(queue, cluster));
    }

    public QueueVO get(String queueId) {
        return toVO(requireVisible(queueId), null);
    }

    // ── 写操作 ──────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public QueueVO create(String clusterId, SaveQueueRequest request) {
        Cluster cluster = clusterService.requireVisible(clusterId);
        requireNameAvailable(cluster.getId(), request.getQueueName(), null);
        Queue queue = new Queue();
        queue.setQueueId(nextQueueId());
        queue.setQueueName(request.getQueueName());
        queue.setClusterId(cluster.getId());
        queue.setStatus("ENABLED");
        queue.setMaxConcurrentTasks(request.getMaxConcurrentTasks() != null ? request.getMaxConcurrentTasks() : 3);
        queue.setMaxWaitingTasks(request.getMaxWaitingTasks() != null ? request.getMaxWaitingTasks() : 50);
        queue.setDefaultPriority(request.getDefaultPriority() != null ? request.getDefaultPriority() : 0);
        queue.setAllowJumpQueue(request.getAllowJumpQueue() != null && request.getAllowJumpQueue());
        queue.setWaitTimeoutSeconds(request.getWaitTimeoutSeconds() != null ? request.getWaitTimeoutSeconds() : 3600);
        queue.setWaitingTaskCount(0);
        queue.setVersion(0);
        queue.setDeleted(false);
        queueMapper.insert(queue);
        log.info("队列已创建 queue={} cluster={} name={}", queue.getQueueId(), clusterId, queue.getQueueName());
        return toVO(queue, cluster);
    }

    @Transactional(rollbackFor = Exception.class)
    public QueueVO update(String queueId, SaveQueueRequest request) {
        Queue queue = requireVisible(queueId);
        // 归属不允许通过本接口改（跨集群搬队列会破坏既有任务的 queue 语义）
        if (request.getClusterId() != null && !request.getClusterId().isBlank()) {
            Cluster target = clusterService.requireVisible(request.getClusterId());
            if (!target.getId().equals(queue.getClusterId())) {
                throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "队列不支持跨集群迁移",
                        Map.of("rule", "QUEUE_CLUSTER_IMMUTABLE"));
            }
        }
        requireNameAvailable(queue.getClusterId(), request.getQueueName(), queue.getId());
        queue.setQueueName(request.getQueueName());
        if (request.getMaxConcurrentTasks() != null) {
            queue.setMaxConcurrentTasks(request.getMaxConcurrentTasks());
        }
        if (request.getMaxWaitingTasks() != null) {
            queue.setMaxWaitingTasks(request.getMaxWaitingTasks());
        }
        if (request.getDefaultPriority() != null) {
            queue.setDefaultPriority(request.getDefaultPriority());
        }
        if (request.getAllowJumpQueue() != null) {
            queue.setAllowJumpQueue(request.getAllowJumpQueue());
        }
        if (request.getWaitTimeoutSeconds() != null) {
            queue.setWaitTimeoutSeconds(request.getWaitTimeoutSeconds());
        }
        queueMapper.updateById(queue);
        return toVO(queue, clusterService.findById(queue.getClusterId()));
    }

    /** 启停队列；禁用前要求无运行中任务（否则在跑步骤会失去队列上下文）。 */
    @Transactional(rollbackFor = Exception.class)
    public QueueVO updateStatus(String queueId, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅允许 ENABLED|DISABLED");
        }
        Queue queue = requireVisible(queueId);
        if (status.equals(queue.getStatus())) {
            return toVO(queue, clusterService.findById(queue.getClusterId()));   // 幂等
        }
        if ("DISABLED".equals(status)) {
            long running = queueMapper.countRunningTasks(queue.getId());
            if (running > 0) {
                throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "队列上有运行中任务，禁止禁用",
                        Map.of("rule", "QUEUE_HAS_RUNNING_TASKS", "running_count", running, "queue_id", queueId));
            }
        }
        queue.setStatus(status);
        queueMapper.updateById(queue);
        log.info("队列状态变更 queue={} → {}", queueId, status);
        return toVO(queue, clusterService.findById(queue.getClusterId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String queueId) {
        Queue queue = requireVisible(queueId);
        long waiting = queueMapper.countWaitingTasks(queue.getId());
        long running = queueMapper.countRunningTasks(queue.getId());
        if (waiting > 0 || running > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "队列上仍有未终结任务，禁止删除",
                    Map.of("rule", "QUEUE_HAS_TASKS", "waiting_count", waiting,
                            "running_count", running, "queue_id", queueId));
        }
        queueMapper.softDelete(queue.getId());
        log.info("队列已删除 queue={}", queueId);
    }

    // ── 内部 ────────────────────────────────────────────────

    private Queue requireVisible(String queueId) {
        Queue visible = findByBusinessId(queueId);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("QUEUE", queueId, () -> findByBusinessId(queueId) != null);
    }

    private Queue findByBusinessId(String queueId) {
        return queueMapper.selectOne(Wrappers.<Queue>lambdaQuery()
                .eq(Queue::getQueueId, queueId)
                .eq(Queue::getDeleted, false));
    }

    private void requireNameAvailable(Long clusterId, String name, Long excludeId) {
        Long existing = queueMapper.selectCount(Wrappers.<Queue>lambdaQuery()
                .eq(Queue::getClusterId, clusterId)
                .eq(Queue::getQueueName, name)
                .eq(Queue::getDeleted, false)
                .ne(excludeId != null, Queue::getId, excludeId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "同集群下队列名称已存在: " + name,
                    Map.of("rule", "QUEUE_NAME_DUPLICATE"));
        }
    }

    private String nextQueueId() {
        String date = DATE.format(java.time.LocalDate.now());
        Long seq = redis.opsForValue().increment("flowops:seq:qu:" + date);
        return "QU-" + date + "-" + (seq != null ? seq : System.currentTimeMillis() % 100000);
    }

    /** 跨表补齐：业务编号 + 集群名（传入的 cluster 为 null 时回查一次）。 */
    private QueueVO toVO(Queue queue, Cluster cluster) {
        QueueVO vo = converter.toVO(queue);
        Cluster resolved = cluster != null ? cluster : clusterService.findById(queue.getClusterId());
        if (resolved != null) {
            vo.setClusterId(resolved.getClusterId());
            vo.setClusterName(resolved.getClusterName());
        }
        return vo;
    }
}
