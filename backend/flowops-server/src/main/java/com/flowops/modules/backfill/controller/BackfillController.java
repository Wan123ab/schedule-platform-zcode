package com.flowops.modules.backfill.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.Idempotent;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.backfill.dto.BackfillBatchVO;
import com.flowops.modules.backfill.dto.BackfillPreviewRequest;
import com.flowops.modules.backfill.dto.BackfillPreviewVO;
import com.flowops.modules.backfill.dto.BackfillSubmitRequest;
import com.flowops.modules.backfill.service.BackfillService;
import com.flowops.modules.task.dto.TaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回填批次接口（M4-S3，CONTRACT §8 表 + docs/07 §6.5 双闸门）。
 *
 * <p><b>数据范围（docs/07 §5.4）</b>：{@code /backfills*} → {@code backfill:read}/{@code backfill:write}
 * + PROJECT 范围 —— {@code backfill_batch.project_id} 已登记进
 * {@code FlowopsDataPermissionHandler}，项目成员只看本项目的批次。</p>
 *
 * <p><b>横切面</b>：{@code POST /backfills} 是 4 个幂等强制端点之一（docs/07 §13 / I-02）；
 * 必审动作 SUBMIT_BACKFILL / PAUSE_BACKFILL / RESUME_BACKFILL / RETRY_BACKFILL_FAILED
 * （docs/07 §7.3，无 CANCEL_BACKFILL —— 取消批次 CONTRACT 无端点，见 README-M4 O-60）。</p>
 */
@RestController
@RequestMapping("/backfills")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;

    /** 预览（不落库；返回 previewHash 供提交闸门回传）。 */
    @PostMapping("/preview")
    @RequiresPermission("schedule:backfill:write")
    public ApiResult<BackfillPreviewVO> preview(@RequestBody @Valid BackfillPreviewRequest request) {
        return ApiResult.ok(backfillService.preview(request));
    }

    /** 提交批次（双闸门：服务端重算 hash 比对 + Redis 预览存在性，5 分钟 TTL）。 */
    @PostMapping
    @RequiresPermission("schedule:backfill:write")
    @Idempotent(required = true)
    @Audited(action = "SUBMIT_BACKFILL", targetType = "BACKFILL")
    public ApiResult<BackfillBatchVO> submit(@RequestBody @Valid BackfillSubmitRequest request) {
        return ApiResult.ok(backfillService.submit(request));
    }

    /** 批次列表（进度区）。 */
    @GetMapping
    @RequiresPermission("schedule:backfill:read")
    @DataScope({"PROJECT"})
    public ApiResult<PageResult<BackfillBatchVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        var result = backfillService.list(status, page, pageSize);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    /** 历史归档（DONE/CANCELLED）。 */
    @GetMapping("/history")
    @RequiresPermission("schedule:backfill:read")
    @DataScope({"PROJECT"})
    public ApiResult<PageResult<BackfillBatchVO>> history(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        var result = backfillService.history(page, pageSize);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{batchId}")
    @RequiresPermission("schedule:backfill:read")
    public ApiResult<BackfillBatchVO> get(@PathVariable String batchId) {
        return ApiResult.ok(backfillService.get(batchId));
    }

    /** 批次任务明细（逐日期的任务列表）。 */
    @GetMapping("/{batchId}/tasks")
    @RequiresPermission("schedule:backfill:read")
    public ApiResult<PageResult<TaskVO>> tasks(@PathVariable String batchId,
                                               @RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "50") long pageSize) {
        var result = backfillService.pageTasks(batchId, page, pageSize);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    /** 暂停（RUNNING → PAUSED；在途任务不中断，docs/06 §12.4）。 */
    @PostMapping("/{batchId}/pause")
    @RequiresPermission("schedule:backfill:write")
    @Audited(action = "PAUSE_BACKFILL", targetType = "BACKFILL", targetIdExpr = "#batchId")
    public ApiResult<BackfillBatchVO> pause(@PathVariable String batchId) {
        return ApiResult.ok(backfillService.pause(batchId));
    }

    /** 继续（PAUSED → RUNNING）。 */
    @PostMapping("/{batchId}/resume")
    @RequiresPermission("schedule:backfill:write")
    @Audited(action = "RESUME_BACKFILL", targetType = "BACKFILL", targetIdExpr = "#batchId")
    public ApiResult<BackfillBatchVO> resume(@PathVariable String batchId) {
        return ApiResult.ok(backfillService.resume(batchId));
    }

    /** 重跑失败（失败计数并入 waiting；为失败日期新建任务，原失败任务保留审计）。 */
    @PostMapping("/{batchId}/retry-failed")
    @RequiresPermission("schedule:backfill:write")
    @Audited(action = "RETRY_BACKFILL_FAILED", targetType = "BACKFILL", targetIdExpr = "#batchId")
    public ApiResult<BackfillBatchVO> retryFailed(@PathVariable String batchId) {
        return ApiResult.ok(backfillService.retryFailed(batchId));
    }
}
