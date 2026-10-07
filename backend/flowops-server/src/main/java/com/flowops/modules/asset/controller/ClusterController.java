package com.flowops.modules.asset.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.asset.dto.ClusterVO;
import com.flowops.modules.asset.dto.ExecutorNodeVO;
import com.flowops.modules.asset.dto.QueueVO;
import com.flowops.modules.asset.dto.SaveClusterRequest;
import com.flowops.modules.asset.dto.SaveExecutorNodeRequest;
import com.flowops.modules.asset.dto.SaveQueueRequest;
import com.flowops.modules.asset.service.ClusterService;
import com.flowops.modules.asset.service.ExecutorNodeService;
import com.flowops.modules.asset.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 集群接口（docs/07 §5.4 / CONTRACT §3；PRD §10.3）。
 *
 * <p><b>数据范围</b>：读接口标注 {@code @DataScope({"ALL","AUTHORIZED_CLUSTER"})} —— 平台管理员看全部，
 * 运维人员只看被授权集群；行级条件由 Mapper 层注入（{@link com.flowops.config.FlowopsDataPermissionHandler}），
 * Controller 只声明语义、不拼 SQL。</p>
 *
 * <p><b>必审动作</b>（docs/07 §7.3 集群与节点 9 个动作）：集群侧占
 * CREATE / UPDATE / DELETE / MAINTENANCE 四个。审计切面异步落库，业务异常也留痕。</p>
 */
@RestController
@RequestMapping("/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterService clusterService;
    private final ExecutorNodeService nodeService;
    private final QueueService queueService;

    // ── 集群本体 ────────────────────────────────────────────

    @GetMapping
    @RequiresPermission("schedule:cluster:read")
    @DataScope({"ALL", "AUTHORIZED_CLUSTER"})
    public ApiResult<PageResult<ClusterVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword) {
        IPage<ClusterVO> result = clusterService.page(page, pageSize, status, keyword);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{clusterId}")
    @RequiresPermission("schedule:cluster:read")
    public ApiResult<ClusterVO> get(@PathVariable String clusterId) {
        return ApiResult.ok(clusterService.get(clusterId));
    }

    @PostMapping
    @RequiresPermission("schedule:cluster:write")
    @Audited(action = "CREATE_CLUSTER", targetType = "CLUSTER")
    public ApiResult<ClusterVO> create(@RequestBody @Valid SaveClusterRequest request) {
        return ApiResult.ok(clusterService.create(request));
    }

    @PutMapping("/{clusterId}")
    @RequiresPermission("schedule:cluster:write")
    @Audited(action = "UPDATE_CLUSTER", targetType = "CLUSTER", targetIdExpr = "#clusterId")
    public ApiResult<ClusterVO> update(@PathVariable String clusterId,
                                       @RequestBody @Valid SaveClusterRequest request) {
        return ApiResult.ok(clusterService.update(clusterId, request));
    }

    /**
     * 维护开关（docs/07 §7.3 的独立动作码 MAINTENANCE_CLUSTER）。
     * 单独开一个端点而不是复用 PUT：维护模式会让集群停止接纳新任务，
     * 审计上需要能与普通编辑区分开，否则"谁把生产集群切进维护"只能翻 diff。
     */
    @PutMapping("/{clusterId}/status")
    @RequiresPermission("schedule:cluster:write")
    @Audited(action = "MAINTENANCE_CLUSTER", targetType = "CLUSTER", targetIdExpr = "#clusterId")
    public ApiResult<ClusterVO> updateStatus(@PathVariable String clusterId,
                                            @RequestBody Map<String, String> body) {
        SaveClusterRequest request = new SaveClusterRequest();
        // 复用 update 的字段语义：这里只允许改 status（DTO 的 @Pattern 会挡住非法值）
        request.setClusterName(clusterService.get(clusterId).getClusterName());
        request.setStatus(body.get("status"));
        return ApiResult.ok(clusterService.update(clusterId, request));
    }

    @DeleteMapping("/{clusterId}")
    @RequiresPermission("schedule:cluster:write")
    @Audited(action = "DELETE_CLUSTER", targetType = "CLUSTER", targetIdExpr = "#clusterId")
    public ApiResult<Void> delete(@PathVariable String clusterId) {
        clusterService.delete(clusterId);
        return ApiResult.ok();
    }

    // ── 集群下的节点（docs/07 §5.4 GET /clusters* + /executor-nodes*）────

    @GetMapping("/{clusterId}/nodes")
    @RequiresPermission("schedule:node:read")
    @DataScope({"ALL", "AUTHORIZED_CLUSTER"})
    public ApiResult<PageResult<ExecutorNodeVO>> nodes(@PathVariable String clusterId,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(required = false) String onlineStatus,
                                                       @RequestParam(required = false) String osType,
                                                       @RequestParam(required = false) String tag) {
        IPage<ExecutorNodeVO> result = nodeService.pageByCluster(clusterId, page, pageSize, onlineStatus, osType, tag);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @PostMapping("/{clusterId}/nodes")
    @RequiresPermission("schedule:node:write")
    @Audited(action = "CREATE_NODE", targetType = "EXECUTOR_NODE")
    public ApiResult<ExecutorNodeVO> createNode(@PathVariable String clusterId,
                                                @RequestBody @Valid SaveExecutorNodeRequest request) {
        return ApiResult.ok(nodeService.create(clusterId, request));
    }

    // ── 集群下的队列 ────────────────────────────────────────

    @GetMapping("/{clusterId}/queues")
    @RequiresPermission("schedule:queue:read")
    @DataScope({"ALL", "AUTHORIZED_CLUSTER"})
    public ApiResult<PageResult<QueueVO>> queues(@PathVariable String clusterId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @RequestParam(required = false) String status) {
        IPage<QueueVO> result = queueService.pageByCluster(clusterId, page, pageSize, status);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @PostMapping("/{clusterId}/queues")
    @RequiresPermission("schedule:queue:write")
    @Audited(action = "CREATE_QUEUE", targetType = "QUEUE")
    public ApiResult<QueueVO> createQueue(@PathVariable String clusterId,
                                          @RequestBody @Valid SaveQueueRequest request) {
        return ApiResult.ok(queueService.create(clusterId, request));
    }
}
