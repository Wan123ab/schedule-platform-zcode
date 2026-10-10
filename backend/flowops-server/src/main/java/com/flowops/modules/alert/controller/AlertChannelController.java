package com.flowops.modules.alert.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.alert.dto.AlertChannelUpdateRequest;
import com.flowops.modules.alert.dto.AlertChannelVO;
import com.flowops.modules.alert.dto.AlertRecordVO;
import com.flowops.modules.alert.dto.AlertTestResultVO;
import com.flowops.modules.alert.service.AlertChannelService;
import com.flowops.modules.alert.service.AlertRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 渠道与告警历史接口（M4-S4，CONTRACT §9）。
 *
 * <p>渠道表初始为空（V5 只种规则）：{@code PUT /alert-channels/{type}} 是 upsert，
 * {@code GET /alert-channels} 对未配置类型返回"未配置"空行供前端渲染。</p>
 */
@RestController
@RequiredArgsConstructor
public class AlertChannelController {

    private final AlertChannelService channelService;
    private final AlertRecordService recordService;

    @GetMapping("/alert-channels")
    @RequiresPermission("schedule:alert:read")
    public ApiResult<List<AlertChannelVO>> list() {
        return ApiResult.ok(channelService.list());
    }

    @PutMapping("/alert-channels/{type}")
    @RequiresPermission("schedule:alert:write")
    public ApiResult<AlertChannelVO> update(@PathVariable String type,
                                            @RequestBody @Valid AlertChannelUpdateRequest request) {
        return ApiResult.ok(channelService.update(type, request));
    }

    @PostMapping("/alert-channels/{type}/test")
    @RequiresPermission("schedule:alert:write")
    @Audited(action = "TEST_CHANNEL", targetType = "ALERT_RULE", targetIdExpr = "#type")
    public ApiResult<AlertTestResultVO> test(@PathVariable String type) {
        return ApiResult.ok(channelService.test(type));
    }

    @GetMapping("/alert-history")
    @RequiresPermission("schedule:alert:read")
    public ApiResult<PageResult<AlertRecordVO>> history(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        var result = recordService.history(eventType, from, to, page, pageSize);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }
}
