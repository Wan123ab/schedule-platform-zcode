package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.modules.asset.converter.ClusterConverter;
import com.flowops.modules.asset.dto.ClusterVO;
import com.flowops.modules.asset.dto.SaveClusterRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 集群服务（docs/05 §3.3 cluster；接口映射 docs/07 §5.4）。
 *
 * <p><b>红线</b>：</p>
 * <ul>
 *   <li>删除闸门用<b>实时 COUNT</b>（42204：集群下仍有执行节点/队列）—— 绝不读 {@code node_total} 快照列
 *       （docs/05 §6.3：冗余计数只用于展示）；</li>
 *   <li>单条读/写一律走 {@link #requireVisible}：查不到时用 {@link ScopeGuard} 区分
 *       40400（真不存在）与 40301（存在但越权），避免运维把"无权限"误读成"数据丢了"；</li>
 *   <li>行级过滤（AUTHORIZED_CLUSTER → {@code cluster.id IN (...)}）由
 *       {@link com.flowops.config.FlowopsDataPermissionHandler} 在 Mapper 层自动注入，
 *       本类<b>不</b>手写数据范围条件 —— 单点收口才好审计。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterService {

    private final ClusterMapper clusterMapper;
    private final IdGen idGen;
    private final ClusterConverter converter;
    private final ScopeGuard scopeGuard;

    // ── 查询 ────────────────────────────────────────────────

    public IPage<ClusterVO> page(long page, long size, String status, String keyword) {
        Page<Cluster> result = clusterMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Cluster>lambdaQuery()
                        .eq(Cluster::getDeleted, false)
                        .eq(status != null && !status.isBlank(), Cluster::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), Cluster::getClusterName, keyword)
                        .orderByAsc(Cluster::getClusterId));
        return result.convert(converter::toVO);
    }

    public ClusterVO get(String clusterId) {
        return converter.toVO(requireVisible(clusterId));
    }

    // ── 写操作 ──────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public ClusterVO create(SaveClusterRequest request) {
        requireNameAvailable(request.getClusterName(), null);
        Cluster cluster = new Cluster();
        cluster.setClusterId(nextClusterId());
        cluster.setClusterName(request.getClusterName());
        cluster.setClusterType(request.getClusterType() != null ? request.getClusterType() : "GENERAL");
        cluster.setStatus(request.getStatus() != null ? request.getStatus() : "NORMAL");
        cluster.setCpuTotal(nvl(request.getCpuTotal()));
        cluster.setGpuTotal(nvl(request.getGpuTotal()));
        cluster.setMemoryTotal(nvl(request.getMemoryTotal()));
        cluster.setDiskTotal(nvl(request.getDiskTotal()));
        // 统计列由定时聚合维护（docs/05 §6.3），新建时从 0 起步
        cluster.setNodeTotal(0);
        cluster.setNodeOnline(0);
        cluster.setNodeOffline(0);
        cluster.setNodeIdle(0);
        cluster.setRunningTaskCount(0);
        cluster.setPendingTaskCount(0);
        cluster.setHistoryTaskCount(0);
        cluster.setVersion(0);
        cluster.setDeleted(false);
        clusterMapper.insert(cluster);
        log.info("集群已创建 cluster={} name={}", cluster.getClusterId(), cluster.getClusterName());
        return converter.toVO(cluster);
    }

    @Transactional(rollbackFor = Exception.class)
    public ClusterVO update(String clusterId, SaveClusterRequest request) {
        Cluster cluster = requireVisible(clusterId);
        requireNameAvailable(request.getClusterName(), cluster.getId());
        cluster.setClusterName(request.getClusterName());
        if (request.getClusterType() != null) {
            cluster.setClusterType(request.getClusterType());
        }
        // 状态只允许人工置 NORMAL/MAINTENANCE：PARTIAL_ABNORMAL/UNAVAILABLE 是心跳推导的结果，
        // 人工改会与心跳扫描打架（docs/06 §8.1），故由 DTO 的 @Pattern 先挡一层
        if (request.getStatus() != null) {
            cluster.setStatus(request.getStatus());
        }
        if (request.getCpuTotal() != null) {
            cluster.setCpuTotal(request.getCpuTotal());
        }
        if (request.getGpuTotal() != null) {
            cluster.setGpuTotal(request.getGpuTotal());
        }
        if (request.getMemoryTotal() != null) {
            cluster.setMemoryTotal(request.getMemoryTotal());
        }
        if (request.getDiskTotal() != null) {
            cluster.setDiskTotal(request.getDiskTotal());
        }
        clusterMapper.updateById(cluster);
        log.info("集群已更新 cluster={} status={}", clusterId, cluster.getStatus());
        return converter.toVO(cluster);
    }

    /** 删除闸门（docs/07 §4.2 的 42204）：集群下仍有节点 → 拒；仍有队列 → 也拒（RESTRICT 外键）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String clusterId) {
        Cluster cluster = requireVisible(clusterId);
        long nodes = clusterMapper.countNodes(cluster.getId());
        if (nodes > 0) {
            throw new BizException(ErrorCode.CLUSTER_HAS_NODES, "集群下仍有执行节点，禁止删除",
                    Map.of("node_count", nodes, "cluster_id", clusterId));
        }
        long queues = clusterMapper.countQueues(cluster.getId());
        if (queues > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "集群下仍有调度队列，禁止删除",
                    Map.of("rule", "CLUSTER_HAS_QUEUES", "queue_count", queues, "cluster_id", clusterId));
        }
        clusterMapper.softDelete(cluster.getId());
        log.info("集群已删除 cluster={}", clusterId);
    }

    // ── 供其他资产域服务复用的归属解析 ────────────────────────

    /** 按业务编号取集群（已应用数据范围；供队列/节点的归属校验与 VO 补齐使用）。 */
    public Cluster requireVisible(String clusterId) {
        Cluster visible = findByBusinessId(clusterId);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("CLUSTER", clusterId, () -> findByBusinessId(clusterId) != null);
    }

    /** 按内部主键补齐集群展示信息（不做越权判定：调用方已在自己的归属链上判过）。 */
    public Cluster findById(Long id) {
        return id == null ? null : clusterMapper.selectById(id);
    }

    // ── 内部 ────────────────────────────────────────────────

    private Cluster findByBusinessId(String clusterId) {
        return clusterMapper.selectOne(Wrappers.<Cluster>lambdaQuery()
                .eq(Cluster::getClusterId, clusterId)
                .eq(Cluster::getDeleted, false));
    }

    /** uk_cluster_name 是 deleted=false 的部分唯一索引 → 这里先给人话错误，而不是让 DB 抛 23505。 */
    private void requireNameAvailable(String name, Long excludeId) {
        Long existing = clusterMapper.selectCount(Wrappers.<Cluster>lambdaQuery()
                .eq(Cluster::getClusterName, name)
                .eq(Cluster::getDeleted, false)
                .ne(excludeId != null, Cluster::getId, excludeId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "集群名称已存在: " + name,
                    Map.of("rule", "CLUSTER_NAME_DUPLICATE"));
        }
    }

    /** 业务编号 {@code CL-yyyyMMdd-####}（口径与实现统一收口在 {@link IdGen}，docs/05 §6.2）。 */
    private String nextClusterId() {
        return idGen.nextDated("CL", "cl");
    }

    private BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private Long nvl(Long value) {
        return value != null ? value : 0L;
    }
}
