package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.asset.ExecutorNodeMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.modules.asset.converter.ExecutorNodeConverter;
import com.flowops.modules.asset.dto.ExecutorNodeVO;
import com.flowops.modules.asset.dto.NodeTestResultVO;
import com.flowops.modules.asset.dto.SaveExecutorNodeRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 执行节点服务（docs/05 §3.3 executor_node；术语 V0.2：不叫 node）。
 *
 * <p><b>本类刻意不做的事</b>：不写 {@code online_status} / {@code last_heartbeat_at} /
 * {@code heartbeat_miss_count} —— 心跳是"事实"不是"配置"，由调度器的心跳链路
 * （{@code POST /internal/nodes/{id}/heartbeat} + HeartbeatScanner 三段阈值）单向维护。
 * 入参 DTO 里没有这些字段，人工污染在编译期就不可能发生。</p>
 *
 * <p><b>禁用闸门（42205，PRD §10.4）</b>：节点上还有 SCHEDULING/RUNNING 的步骤时拒绝禁用，
 * 用 task_step 的实时 COUNT 判定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutorNodeService {

    private final ExecutorNodeMapper nodeMapper;
    private final CredentialMapper credentialMapper;
    private final ClusterService clusterService;
    private final IdGen idGen;
    private final ExecutorNodeConverter converter;
    private final ScopeGuard scopeGuard;
    private final SecretCryptoService crypto;
    private final ExecutorClient executorClient;

    // ── 查询 ────────────────────────────────────────────────

    /**
     * 某集群下的节点分页（集群详情页的节点 tab）。
     *
     * <p>{@code tags} 过滤用 {@code @>}（数组包含）运算符 —— lambda 表达式无法表达数组运算符，
     * 故这里用 MP 的 {@code apply} 条件片段（{0} 仍是预编译参数绑定，非字符串拼接，无注入面）。
     * 这是本类唯一一处非 lambda 条件，且只作用于 text[] 列。</p>
     */
    public IPage<ExecutorNodeVO> pageByCluster(String clusterId, long page, long size,
                                               String onlineStatus, String osType, String tag) {
        Cluster cluster = clusterService.requireVisible(clusterId);
        Page<ExecutorNode> result = nodeMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<ExecutorNode>lambdaQuery()
                        .eq(ExecutorNode::getDeleted, false)
                        .eq(ExecutorNode::getClusterId, cluster.getId())
                        .eq(onlineStatus != null && !onlineStatus.isBlank(), ExecutorNode::getOnlineStatus, onlineStatus)
                        .eq(osType != null && !osType.isBlank(), ExecutorNode::getOsType, osType)
                        .apply(tag != null && !tag.isBlank(), "tags @> ARRAY[{0}]::text[]", tag)
                        .orderByAsc(ExecutorNode::getExecutorNodeId));
        return result.convert(node -> toVO(node, cluster));
    }

    public ExecutorNodeVO get(String nodeId) {
        return toVO(requireVisible(nodeId), null);
    }

    // ── 写操作 ──────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public ExecutorNodeVO create(String clusterId, SaveExecutorNodeRequest request) {
        Cluster cluster = clusterService.requireVisible(clusterId);
        requireIpAvailable(cluster.getId(), request.getIp(), null);
        Long credentialRefId = resolveCredentialRefId(request.getCredentialId());
        ExecutorNode node = new ExecutorNode();
        node.setExecutorNodeId(nextNodeId());
        node.setClusterId(cluster.getId());
        applyRequest(node, request, credentialRefId);
        // 心跳与用量字段的初值：UNKNOWN = "还没收到过心跳"，而不是"离线"（两者告警语义不同）
        node.setOnlineStatus("UNKNOWN");
        node.setHeartbeatMissCount(0);
        node.setCpuUsed(BigDecimal.ZERO);
        node.setGpuUsed(BigDecimal.ZERO);
        node.setMemoryUsed(0L);
        node.setDiskUsed(0L);
        node.setRunningTaskCount(0);
        node.setVersion(0);
        node.setDeleted(false);
        nodeMapper.insert(node);
        log.info("执行节点已创建 node={} cluster={} ip={}", node.getExecutorNodeId(), clusterId, node.getIp());
        return toVO(node, cluster);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExecutorNodeVO update(String nodeId, SaveExecutorNodeRequest request) {
        ExecutorNode node = requireVisible(nodeId);
        // 归属不可变更（R5：节点不可跨集群）
        if (request.getClusterId() != null && !request.getClusterId().isBlank()) {
            Cluster target = clusterService.requireVisible(request.getClusterId());
            if (!target.getId().equals(node.getClusterId())) {
                throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "执行节点不支持跨集群迁移",
                        Map.of("rule", "NODE_CLUSTER_IMMUTABLE"));
            }
        }
        requireIpAvailable(node.getClusterId(), request.getIp(), node.getId());
        Long credentialRefId = resolveCredentialRefId(request.getCredentialId());
        applyRequest(node, request, credentialRefId);
        nodeMapper.updateById(node);
        return toVO(node, clusterService.findById(node.getClusterId()));
    }

    /**
     * 启用/禁用（docs/07 §5.4：{@code PUT /executor-nodes/{id}/enabled}，DataScope = AUTHORIZED_CLUSTER）。
     * 禁用闸门 = task_step 实时 COUNT > 0 → 42205。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutorNodeVO setEnabled(String nodeId, boolean enabled) {
        ExecutorNode node = requireVisible(nodeId);
        if (Boolean.valueOf(enabled).equals(node.getEnabled())) {
            return toVO(node, clusterService.findById(node.getClusterId()));   // 幂等
        }
        if (!enabled) {
            long active = nodeMapper.countActiveStepsOnNode(node.getClusterId(), node.getIp());
            if (active > 0) {
                throw new BizException(ErrorCode.NODE_OCCUPIED, "节点被运行中任务占用，禁止禁用",
                        Map.of("node_id", nodeId, "active_step_count", active));
            }
        }
        node.setEnabled(enabled);
        nodeMapper.updateById(node);
        log.info("执行节点 {} node={}", enabled ? "已启用" : "已禁用", nodeId);
        return toVO(node, clusterService.findById(node.getClusterId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String nodeId) {
        ExecutorNode node = requireVisible(nodeId);
        long active = nodeMapper.countActiveStepsOnNode(node.getClusterId(), node.getIp());
        if (active > 0) {
            throw new BizException(ErrorCode.NODE_OCCUPIED, "节点被运行中任务占用，禁止删除",
                    Map.of("node_id", nodeId, "active_step_count", active));
        }
        nodeMapper.softDelete(node.getId());
        log.info("执行节点已删除 node={}", nodeId);
    }

    /**
     * 节点连通性测试（docs/07 §5.4，权限点 {@code schedule:node:test}，必审动作 TEST_NODE）。
     *
     * <p>真实 SSH 握手，不是 TCP 探测 —— 因为运维要复现的是"调度器能不能登进去跑命令"，
     * 端口通但认证失败是最常见的两类故障之一，只探端口会漏掉它。</p>
     *
     * <p><b>明文纪律</b>：secret 解密后只存在于本方法栈内，不写日志、不进返回值
     * （返回体只有 boolean + 摘要，见 {@link NodeTestResultVO}）。</p>
     */
    public NodeTestResultVO testConnectivity(String nodeId) {
        ExecutorNode node = requireVisible(nodeId);
        if (!"LINUX".equals(node.getOsType())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "一期仅支持 Linux 节点连通性测试（Q-02）",
                    Map.of("rule", "NODE_OS_UNSUPPORTED"));
        }
        if (node.getCredentialRefId() == null) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "节点未绑定凭据，无法测试连通性",
                    Map.of("rule", "NODE_CREDENTIAL_REQUIRED"));
        }
        Credential credential = credentialMapper.selectById(node.getCredentialRefId());
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "节点绑定的凭据不存在: " + node.getCredentialRefId(),
                    Map.of("resource_type", "CREDENTIAL", "resource_id", String.valueOf(node.getCredentialRefId())));
        }
        String secretMaterial = crypto.decrypt(credential.getSecretEncrypted());
        long startedAt = System.nanoTime();
        boolean success = executorClient.testConnection(node.getIp(), credential.getUsername(), secretMaterial);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("节点连通性测试 node={} ip={} success={} elapsed={}ms", nodeId, node.getIp(), success, elapsedMs);
        return new NodeTestResultVO(success,
                success ? "SSH 握手成功" : "SSH 握手失败（凭据或网络问题，详见服务端日志）", elapsedMs);
    }

    // ── 内部 ────────────────────────────────────────────────

    private void applyRequest(ExecutorNode node, SaveExecutorNodeRequest request, Long credentialRefId) {
        node.setExecutorNodeName(request.getExecutorNodeName());
        node.setIp(request.getIp());
        node.setOsType(request.getOsType());
        node.setConnectType(request.getConnectType() != null ? request.getConnectType() : "SSH");
        node.setCredentialRefId(credentialRefId);
        node.setTags(request.getTags() != null ? request.getTags() : new String[0]);
        node.setMaxConcurrentSteps(request.getMaxConcurrentSteps());
        if (request.getCpuTotal() != null) {
            node.setCpuTotal(request.getCpuTotal());
        } else if (node.getCpuTotal() == null) {
            node.setCpuTotal(BigDecimal.ZERO);
        }
        if (request.getGpuTotal() != null) {
            node.setGpuTotal(request.getGpuTotal());
        } else if (node.getGpuTotal() == null) {
            node.setGpuTotal(BigDecimal.ZERO);
        }
        if (request.getMemoryTotal() != null) {
            node.setMemoryTotal(request.getMemoryTotal());
        } else if (node.getMemoryTotal() == null) {
            node.setMemoryTotal(0L);
        }
        if (request.getDiskTotal() != null) {
            node.setDiskTotal(request.getDiskTotal());
        } else if (node.getDiskTotal() == null) {
            node.setDiskTotal(0L);
        }
        node.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    /**
     * 节点可见性：取不到时由 {@link ScopeGuard} 区分 40400（真不存在）与 40301（越权）。
     *
     * <p>公开而非私有：算子试运行（{@code OperatorDryRunService}）要挑一台节点执行，
     * 与连通性测试走的是同一条"这台机器我能不能用"的判定 —— 复制一份到试运行里，
     * 迟早出现"节点管理页进得去、试运行选不到"这类看起来像 bug 的权限差异
     * （与 {@code WorkflowAccessGuard} 独立成类的理由同源）。</p>
     */
    public ExecutorNode requireVisible(String nodeId) {
        ExecutorNode visible = findByBusinessId(nodeId);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("EXECUTOR_NODE", nodeId, () -> findByBusinessId(nodeId) != null);
    }

    private ExecutorNode findByBusinessId(String nodeId) {
        return nodeMapper.selectOne(Wrappers.<ExecutorNode>lambdaQuery()
                .eq(ExecutorNode::getExecutorNodeId, nodeId)
                .eq(ExecutorNode::getDeleted, false));
    }

    /** uk_node_cluster_ip：同集群内 IP 唯一（同机多实例须用不同 IP:port 约定，一期不支持）。 */
    private void requireIpAvailable(Long clusterId, String ip, Long excludeId) {
        Long existing = nodeMapper.selectCount(Wrappers.<ExecutorNode>lambdaQuery()
                .eq(ExecutorNode::getClusterId, clusterId)
                .eq(ExecutorNode::getIp, ip)
                .eq(ExecutorNode::getDeleted, false)
                .ne(excludeId != null, ExecutorNode::getId, excludeId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "同集群内该 IP 已存在: " + ip,
                    Map.of("rule", "NODE_IP_DUPLICATE"));
        }
    }

    /**
     * 凭据引用解析（R6）：入参给的是**业务编号** {@code CR-xxxx}（内部 Long 主键不出网，D-15 同源口径），
     * 这里一次性翻译成内部主键供落库使用。
     *
     * <p>引用一个不存在的凭据会让节点到执行期才炸，属"早失败"原则的适用范围，故在写路径直接 40400。</p>
     *
     * @return 内部主键；入参为空（未绑定凭据）时返回 {@code null}
     */
    private Long resolveCredentialRefId(String credentialBusinessId) {
        if (credentialBusinessId == null || credentialBusinessId.isBlank()) {
            return null;
        }
        Credential credential = credentialMapper.selectOne(Wrappers.<Credential>lambdaQuery()
                .eq(Credential::getCredentialId, credentialBusinessId)
                .eq(Credential::getDeleted, false));
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "引用的凭据不存在: " + credentialBusinessId,
                    Map.of("resource_type", "CREDENTIAL", "resource_id", credentialBusinessId));
        }
        return credential.getId();
    }

    private String nextNodeId() {
        return idGen.nextDated("EN", "en");
    }

    private ExecutorNodeVO toVO(ExecutorNode node, Cluster cluster) {
        ExecutorNodeVO vo = converter.toVO(node);
        Cluster resolved = cluster != null ? cluster : clusterService.findById(node.getClusterId());
        if (resolved != null) {
            vo.setClusterId(resolved.getClusterId());
            vo.setClusterName(resolved.getClusterName());
        }
        // 内部主键 → 业务编号回填（converter 已 ignore，跨口径字段只能在这里补）
        if (node.getCredentialRefId() != null) {
            Credential credential = credentialMapper.selectById(node.getCredentialRefId());
            if (credential != null) {
                vo.setCredentialId(credential.getCredentialId());
                vo.setCredentialName(credential.getCredentialName());
            }
        }
        return vo;
    }
}
