package com.flowops.modules.asset.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.asset.dto.QueueVO;
import com.flowops.modules.asset.dto.SaveQueueRequest;
import com.flowops.modules.asset.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 队列接口（docs/05 §3.3 queue；CONTRACT 未单列该域，端点按 §5.4 的 {@code queue:read/write} 权限点补齐）。
 *
 * <p>创建入口在集群下（{@code POST /clusters/{id}/queues}，见 ClusterController），
 * 因为队列<b>必须</b>有归属集群；后续的读改删以队列自身编号寻址。</p>
 */
@RestController
@RequestMapping("/queues")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @GetMapping("/{queueId}")
    @RequiresPermission("schedule:queue:read")
    public ApiResult<QueueVO> get(@PathVariable String queueId) {
        return ApiResult.ok(queueService.get(queueId));
    }

    @PutMapping("/{queueId}")
    @RequiresPermission("schedule:queue:write")
    @Audited(action = "UPDATE_QUEUE", targetType = "QUEUE", targetIdExpr = "#queueId")
    public ApiResult<QueueVO> update(@PathVariable String queueId,
                                     @RequestBody @Valid SaveQueueRequest request) {
        return ApiResult.ok(queueService.update(queueId, request));
    }

    /** 启停队列（禁用前要求队列上无运行中任务）。 */
    @PutMapping("/{queueId}/status")
    @RequiresPermission("schedule:queue:write")
    @Audited(action = "UPDATE_QUEUE", targetType = "QUEUE", targetIdExpr = "#queueId")
    public ApiResult<QueueVO> updateStatus(@PathVariable String queueId,
                                          @RequestBody Map<String, String> body) {
        return ApiResult.ok(queueService.updateStatus(queueId, body.get("status")));
    }

    @DeleteMapping("/{queueId}")
    @RequiresPermission("schedule:queue:write")
    @Audited(action = "DELETE_QUEUE", targetType = "QUEUE", targetIdExpr = "#queueId")
    public ApiResult<Void> delete(@PathVariable String queueId) {
        queueService.delete(queueId);
        return ApiResult.ok();
    }
}
