package com.flowops.modules.workflow.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.workflow.dto.SaveTriggerRequest;
import com.flowops.modules.workflow.dto.TriggerVO;
import com.flowops.modules.workflow.service.TriggerService;
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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 触发器接口（docs/07 §6.4；权限映射见 §5.4 的 {@code /triggers*} 行）。
 *
 * <p><b>数据范围的例外</b>：docs/07 §5.4 标注触发器是 PROJECT 范围，但
 * {@code trigger} 表没有 {@code project_id} 列（docs/05 §3.4 的 DDL 如此），
 * 行级数据权限<b>不</b>注册它。可见性改为"借父工作流判定"：列表必须带
 * {@code workflow_id}，先判工作流可见再取其触发器；单查先取行再回判父工作流。
 * 语义上与 PROJECT 范围等价（触发器永远跟着工作流走），只是实现层不同 ——
 * 与 workflow_version 同一模式。</p>
 *
 * <p><b>必审动作</b>（docs/07 §7.3）：CREATE_TRIGGER / UPDATE_TRIGGER / DELETE_TRIGGER。
 * 查询类（含 cron-preview）不审计。</p>
 */
@RestController
@RequestMapping("/triggers")
@RequiredArgsConstructor
public class TriggerController {

    private final TriggerService triggerService;

    /** 某工作流下的触发器列表。{@code workflowId} 必填 —— 触发器脱离工作流没有意义。 */
    @GetMapping
    @RequiresPermission("schedule:trigger:read")
    public ApiResult<List<TriggerVO>> list(@RequestParam String workflowId) {
        return ApiResult.ok(triggerService.listByWorkflow(workflowId));
    }

    /**
     * cron 预览：接下来 N 次触发时间（前端 CronInput 至少展示 5 个，docs/04 §658）。
     * 表达式非法 → 42216。路径是字面量，Spring 优先于 {@code /{triggerId}} 模板匹配。
     */
    @GetMapping("/cron-preview")
    @RequiresPermission("schedule:trigger:read")
    public ApiResult<List<OffsetDateTime>> cronPreview(@RequestParam("cron_expression") String cronExpression,
                                                       @RequestParam(required = false) String timezone,
                                                       @RequestParam(required = false) Integer count) {
        return ApiResult.ok(triggerService.previewCron(cronExpression, timezone, count));
    }

    @PostMapping
    @RequiresPermission("schedule:trigger:write")
    @Audited(action = "CREATE_TRIGGER", targetType = "TRIGGER")
    public ApiResult<TriggerVO> create(@RequestBody @Valid SaveTriggerRequest request) {
        return ApiResult.ok(triggerService.create(request));
    }

    @GetMapping("/{triggerId}")
    @RequiresPermission("schedule:trigger:read")
    public ApiResult<TriggerVO> get(@PathVariable String triggerId) {
        return ApiResult.ok(triggerService.get(triggerId));
    }

    @PutMapping("/{triggerId}")
    @RequiresPermission("schedule:trigger:write")
    @Audited(action = "UPDATE_TRIGGER", targetType = "TRIGGER", targetIdExpr = "#triggerId")
    public ApiResult<TriggerVO> update(@PathVariable String triggerId,
                                       @RequestBody @Valid SaveTriggerRequest request) {
        return ApiResult.ok(triggerService.update(triggerId, request));
    }

    @DeleteMapping("/{triggerId}")
    @RequiresPermission("schedule:trigger:write")
    @Audited(action = "DELETE_TRIGGER", targetType = "TRIGGER", targetIdExpr = "#triggerId")
    public ApiResult<Void> delete(@PathVariable String triggerId) {
        triggerService.delete(triggerId);
        return ApiResult.ok(null);
    }
}
