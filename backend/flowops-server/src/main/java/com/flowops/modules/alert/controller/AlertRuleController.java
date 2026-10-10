package com.flowops.modules.alert.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.alert.dto.AlertChannelUpdateRequest;
import com.flowops.modules.alert.dto.AlertChannelVO;
import com.flowops.modules.alert.dto.AlertEnabledRequest;
import com.flowops.modules.alert.dto.AlertRecordVO;
import com.flowops.modules.alert.dto.AlertRuleUpsertRequest;
import com.flowops.modules.alert.dto.AlertRuleVO;
import com.flowops.modules.alert.dto.AlertTestResultVO;
import com.flowops.modules.alert.service.AlertChannelService;
import com.flowops.modules.alert.service.AlertRecordService;
import com.flowops.modules.alert.service.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 告警中心接口（M4-S4，CONTRACT §9 表，9 端点逐一对应）。
 *
 * <p><b>权限（docs/07 §5.4）</b>：{@code /alert-rules*}、{@code /alert-channels*}、
 * {@code /alert-history} → {@code schedule:alert:read}（读）/ {@code schedule:alert:write}
 * （写），PROJECT / ALL 范围。规则/记录本身带 project 维度但 alert_rule 未登记数据权限
 * （管理员视图语义为主，docs/07 §5.4 的 PROJECT/ALL 混合范围），偏离见 README-M4 O-68。</p>
 *
 * <p><b>必审动作（docs/07 §7.3）</b>：CREATE_ALERT_RULE / UPDATE_ALERT_RULE（含启停）/
 * DELETE_ALERT_RULE / TEST_CHANNEL，targetType=ALERT_RULE。</p>
 */
@RestController
@RequestMapping("/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService ruleService;

    @GetMapping
    @RequiresPermission("schedule:alert:read")
    public ApiResult<List<AlertRuleVO>> list(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResult.ok(ruleService.list(eventType, enabled));
    }

    @PostMapping
    @RequiresPermission("schedule:alert:write")
    @Audited(action = "CREATE_ALERT_RULE", targetType = "ALERT_RULE")
    public ApiResult<AlertRuleVO> create(@RequestBody @Valid AlertRuleUpsertRequest request) {
        return ApiResult.ok(ruleService.create(request));
    }

    @PutMapping("/{ruleId}")
    @RequiresPermission("schedule:alert:write")
    @Audited(action = "UPDATE_ALERT_RULE", targetType = "ALERT_RULE", targetIdExpr = "#ruleId")
    public ApiResult<AlertRuleVO> update(@PathVariable String ruleId,
                                         @RequestBody @Valid AlertRuleUpsertRequest request) {
        return ApiResult.ok(ruleService.update(ruleId, request));
    }

    /** 启停（归并 UPDATE_ALERT_RULE 审计码 —— docs/07 §7.3 无独立 TOGGLE 动作，见 O-68）。 */
    @PutMapping("/{ruleId}/enabled")
    @RequiresPermission("schedule:alert:write")
    @Audited(action = "UPDATE_ALERT_RULE", targetType = "ALERT_RULE", targetIdExpr = "#ruleId")
    public ApiResult<AlertRuleVO> toggleEnabled(@PathVariable String ruleId,
                                                @RequestBody @Valid AlertEnabledRequest request) {
        return ApiResult.ok(ruleService.toggleEnabled(ruleId, request.getEnabled()));
    }

    @DeleteMapping("/{ruleId}")
    @RequiresPermission("schedule:alert:write")
    @Audited(action = "DELETE_ALERT_RULE", targetType = "ALERT_RULE", targetIdExpr = "#ruleId")
    public ApiResult<Void> delete(@PathVariable String ruleId) {
        ruleService.delete(ruleId);
        return ApiResult.ok(null);
    }
}
