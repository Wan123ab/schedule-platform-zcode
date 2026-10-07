package com.flowops.modules.asset.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.asset.dto.ExecutorNodeVO;
import com.flowops.modules.asset.dto.NodeTestResultVO;
import com.flowops.modules.asset.dto.SaveExecutorNodeRequest;
import com.flowops.modules.asset.service.ExecutorNodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 执行节点接口（docs/07 §5.4 / CONTRACT §3）。
 *
 * <p>路径用 {@code /executor-nodes} 而不是 {@code /nodes} —— V0.2 术语：实体名是
 * <b>executorNode</b>，不是 node（docs/00 §4）。术语与路由一致，前端契约才不会漂移。</p>
 *
 * <p>{@code PUT .../enabled} 的 DataScope 是 AUTHORIZED_CLUSTER：运维<b>只能</b>启停被授权集群里的机器
 * （docs/07 §5.4 唯一显式标注 AUTHORIZED_CLUSTER 的写端点）。</p>
 */
@RestController
@RequestMapping("/executor-nodes")
@RequiredArgsConstructor
public class ExecutorNodeController {

    private final ExecutorNodeService nodeService;

    @GetMapping("/{nodeId}")
    @RequiresPermission("schedule:node:read")
    public ApiResult<ExecutorNodeVO> get(@PathVariable String nodeId) {
        return ApiResult.ok(nodeService.get(nodeId));
    }

    @PutMapping("/{nodeId}")
    @RequiresPermission("schedule:node:write")
    @Audited(action = "UPDATE_NODE", targetType = "EXECUTOR_NODE", targetIdExpr = "#nodeId")
    public ApiResult<ExecutorNodeVO> update(@PathVariable String nodeId,
                                            @RequestBody @Valid SaveExecutorNodeRequest request) {
        return ApiResult.ok(nodeService.update(nodeId, request));
    }

    /** 启停节点（42205 闸门：有运行中步骤 → 拒绝禁用）。 */
    @PutMapping("/{nodeId}/enabled")
    @RequiresPermission("schedule:node:write")
    @DataScope({"ALL", "AUTHORIZED_CLUSTER"})
    @Audited(action = "TOGGLE_NODE", targetType = "EXECUTOR_NODE", targetIdExpr = "#nodeId")
    public ApiResult<ExecutorNodeVO> setEnabled(@PathVariable String nodeId,
                                                @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResult.ok(nodeService.setEnabled(nodeId, enabled));
    }

    @DeleteMapping("/{nodeId}")
    @RequiresPermission("schedule:node:write")
    @Audited(action = "DELETE_NODE", targetType = "EXECUTOR_NODE", targetIdExpr = "#nodeId")
    public ApiResult<Void> delete(@PathVariable String nodeId) {
        nodeService.delete(nodeId);
        return ApiResult.ok();
    }

    /** 连通性测试（真实 SSH 握手；权限点 schedule:node:test 只有平台管理员与运维人员有）。 */
    @PostMapping("/{nodeId}/test")
    @RequiresPermission("schedule:node:test")
    @DataScope({"ALL", "AUTHORIZED_CLUSTER"})
    @Audited(action = "TEST_NODE", targetType = "EXECUTOR_NODE", targetIdExpr = "#nodeId")
    public ApiResult<NodeTestResultVO> test(@PathVariable String nodeId) {
        return ApiResult.ok(nodeService.testConnectivity(nodeId));
    }
}
