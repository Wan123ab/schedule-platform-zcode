package com.flowops.modules.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.workflow.Trigger;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.common.context.ScopeContext;
import com.flowops.modules.workflow.converter.TriggerConverter;
import com.flowops.modules.workflow.dto.SaveTriggerRequest;
import com.flowops.modules.workflow.dto.TriggerVO;
import com.flowops.modules.workflow.validator.TriggerConfigValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 触发器服务（docs/07 §6.4；PRD §10.9）。
 *
 * <p><b>可见性走父 workflow</b>（与 workflow_version 同一模式，见
 * {@link WorkflowAccessGuard}）：{@code trigger} 表没有 {@code project_id} 列，
 * 数据权限拦截器<b>不</b>注册本表；所有读写先取行，再判父工作流可见性。
 * 因此本类所有 Mapper 查询都包在 {@link ScopeContext#withoutScope} 里 ——
 * 行级过滤对这张表不生效，越权判定全部由 WorkflowAccessGuard 承担。</p>
 *
 * <p><b>next_fire_time 的职责边界</b>：创建/更新时算"下一次什么时候响"（给列表页
 * 展示 + 调度器扫表起点）；响过之后的推进、停机补跑（catch-up）属于调度器
 * （docs/06 §11.2，M4 范围），本类只在配置变化时重算。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TriggerService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final TriggerMapper triggerMapper;
    private final WorkflowService workflowService;
    private final TriggerConverter converter;
    private final IdGen idGen;
    private final ObjectMapper objectMapper;

    // ── 查询 ────────────────────────────────────────────────

    /** 某工作流下的全部触发器（按创建时间倒序）。可见性由父工作流一次判定。 */
    public List<TriggerVO> listByWorkflow(String workflowBusinessId) {
        Workflow workflow = workflowService.requireVisible(workflowBusinessId);
        List<Trigger> rows = ScopeContext.withoutScope(() -> triggerMapper.selectList(
                Wrappers.<Trigger>lambdaQuery()
                        .eq(Trigger::getWorkflowId, workflow.getId())
                        .eq(Trigger::getDeleted, false)
                        .orderByDesc(Trigger::getCreatedAt)));
        return rows.stream().map(t -> toVO(t, workflow)).toList();
    }

    public TriggerVO get(String triggerBusinessId) {
        Trigger trigger = requireVisible(triggerBusinessId);
        Workflow workflow = workflowService.requireVisibleById(trigger.getWorkflowId());
        return toVO(trigger, workflow);
    }

    // ── 写路径 ──────────────────────────────────────────────

    @Transactional
    public TriggerVO create(SaveTriggerRequest request) {
        requireSupportedType(request.getTriggerType());
        validateConfig(request);
        Workflow workflow = workflowService.requireVisible(requireWorkflowId(request));
        requireNameAvailable(workflow.getId(), request.getTriggerName(), null);

        Trigger trigger = new Trigger();
        applyRequest(trigger, request);
        trigger.setTriggerId(idGen.next("TRG", "trg"));
        trigger.setWorkflowId(workflow.getId());
        // enabled / catch-up 缺省值显式写入内存实体：让"立即返回的 VO"与库里 DDL 默认值一致
        if (trigger.getEnabled() == null) {
            trigger.setEnabled(true);
        }
        if (trigger.getCatchUpEnabled() == null) {
            trigger.setCatchUpEnabled(true);
        }
        if (trigger.getCatchUpMaxTimes() == null) {
            trigger.setCatchUpMaxTimes(3);
        }
        trigger.setNextFireTime(computeNextFireTime(trigger));
        triggerMapper.insert(trigger);
        log.info("触发器已创建 {} workflow={}", trigger.getTriggerId(), workflow.getWorkflowId());
        return toVO(trigger, workflow);
    }

    @Transactional
    public TriggerVO update(String triggerBusinessId, SaveTriggerRequest request) {
        Trigger trigger = requireVisible(triggerBusinessId);
        Workflow workflow = workflowService.requireVisibleById(trigger.getWorkflowId());
        requireSupportedType(request.getTriggerType());
        validateConfig(request);
        requireNameAvailable(trigger.getWorkflowId(), request.getTriggerName(), trigger.getId());

        boolean fireScheduleChanged = !sameFireSchedule(trigger, request);
        applyRequest(trigger, request);
        // workflowId 不可变更：挂靠关系改了，"这个触发器属于谁"的审计链就断了
        trigger.setWorkflowId(workflow.getId());
        if (fireScheduleChanged) {
            trigger.setNextFireTime(computeNextFireTime(trigger));
        }
        triggerMapper.updateById(trigger);
        return toVO(trigger, workflow);
    }

    @Transactional
    public void delete(String triggerBusinessId) {
        Trigger trigger = requireVisible(triggerBusinessId);
        workflowService.requireVisibleById(trigger.getWorkflowId());
        triggerMapper.softDelete(trigger.getId());
        log.info("触发器已软删 {}", trigger.getTriggerId());
    }

    // ── cron 预览 ───────────────────────────────────────────

    /**
     * 预览接下来 N 次触发时间（docs/04 §658：前端 CronInput 至少展示 5 个）。
     * 表达式非法 → 42216。count 缺省 5、上限 20（防止恶意大 count 白耗 CPU）。
     */
    public List<OffsetDateTime> previewCron(String cronExpression, String timezone, Integer count) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(requireNonBlank(cronExpression, "cron 表达式必填"));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.CRON_INVALID, "cron 表达式非法：" + e.getMessage());
        }
        ZoneId zone = resolveZone(timezone);
        OffsetDateTime cursor = OffsetDateTime.now(zone);
        int n = count == null ? 5 : Math.min(Math.max(count, 1), 20);
        List<OffsetDateTime> times = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) {
                break; // cron 有尽头（如只匹配 2026 年）——如实返回不足 n 个
            }
            times.add(cursor);
        }
        return List.copyOf(times);
    }

    // ── 工作流停用联动（WorkflowService.disable 调用） ────────

    @Transactional
    public void disableByWorkflow(Long workflowId) {
        triggerMapper.disableByWorkflowId(workflowId);
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 取行 + 判父工作流可见性（40301/40400 单点在 WorkflowAccessGuard）。
     * withoutScope 的纪律：返回的实体只用于鉴权探测与所属关系，不直接出网。
     */
    private Trigger requireVisible(String triggerBusinessId) {
        Trigger trigger = ScopeContext.withoutScope(() -> triggerMapper.selectOne(
                Wrappers.<Trigger>lambdaQuery()
                        .eq(Trigger::getTriggerId, triggerBusinessId)
                        .eq(Trigger::getDeleted, false)));
        if (trigger == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "触发器不存在: " + triggerBusinessId);
        }
        // 存在但父工作流不可见 → 越权（40301），由 guard 抛出
        workflowService.requireVisibleById(trigger.getWorkflowId());
        return trigger;
    }

    private void requireSupportedType(String triggerType) {
        if (!"MANUAL".equals(triggerType) && !"CRON".equals(triggerType)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "触发器类型仅支持 MANUAL / CRON（API / EVENT 一期置灰，见 docs/07 §11）: " + triggerType);
        }
    }

    private void validateConfig(SaveTriggerRequest request) {
        List<TriggerConfigValidator.Violation> violations = TriggerConfigValidator.validate(
                request.getTriggerType(), request.getCronExpression(), request.getPeriodSeconds(),
                request.getTimezone(), request.getEffectiveStart(), request.getEffectiveEnd());
        if (!violations.isEmpty()) {
            TriggerConfigValidator.Violation first = violations.get(0);
            throw new BizException(first.code(), first.message(),
                    Map.of("errors", violations.stream().map(TriggerConfigValidator.Violation::message).toList()));
        }
    }

    private String requireWorkflowId(SaveTriggerRequest request) {
        if (request.getWorkflowId() == null || request.getWorkflowId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属工作流必填");
        }
        return request.getWorkflowId();
    }

    private void requireNameAvailable(Long workflowId, String name, Long excludeId) {
        Long existing = ScopeContext.withoutScope(() -> triggerMapper.selectCount(
                Wrappers.<Trigger>lambdaQuery()
                        .eq(Trigger::getWorkflowId, workflowId)
                        .eq(Trigger::getTriggerName, name)
                        .eq(Trigger::getDeleted, false)
                        .ne(excludeId != null, Trigger::getId, excludeId)));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "同工作流下触发器名称已存在: " + name,
                    Map.of("rule", "TRIGGER_NAME_DUPLICATE"));
        }
    }

    private void applyRequest(Trigger trigger, SaveTriggerRequest request) {
        trigger.setTriggerName(request.getTriggerName());
        trigger.setTriggerType(request.getTriggerType());
        trigger.setCronExpression("CRON".equals(request.getTriggerType())
                ? trimToNull(request.getCronExpression()) : null);
        trigger.setPeriodSeconds("CRON".equals(request.getTriggerType()) ? request.getPeriodSeconds() : null);
        trigger.setTimezone(trimToNull(request.getTimezone()) != null
                ? request.getTimezone().trim() : DEFAULT_TIMEZONE);
        trigger.setEffectiveRange(effectiveRangeJson(request));
        trigger.setEnabled(request.getEnabled() == null ? trigger.getEnabled() : request.getEnabled());
        trigger.setCatchUpEnabled(request.getCatchUpEnabled() == null
                ? trigger.getCatchUpEnabled() : request.getCatchUpEnabled());
        trigger.setCatchUpMaxTimes(request.getCatchUpMaxTimes() == null
                ? trigger.getCatchUpMaxTimes() : request.getCatchUpMaxTimes());
        trigger.setRunParams(toJson(request.getRunParams(), "{}"));
    }

    /** cron/周期/时区任一变化都算"响了什么时候"变了 → 重算 next_fire_time。 */
    private boolean sameFireSchedule(Trigger trigger, SaveTriggerRequest request) {
        String newTimezone = trimToNull(request.getTimezone()) != null
                ? request.getTimezone().trim() : DEFAULT_TIMEZONE;
        boolean typeIsCron = "CRON".equals(request.getTriggerType());
        return java.util.Objects.equals(trigger.getTriggerType(), request.getTriggerType())
                && java.util.Objects.equals(trigger.getCronExpression(),
                        typeIsCron ? trimToNull(request.getCronExpression()) : null)
                && java.util.Objects.equals(trigger.getPeriodSeconds(),
                        typeIsCron ? request.getPeriodSeconds() : null)
                && java.util.Objects.equals(trigger.getTimezone(), newTimezone);
    }

    /** 配置变化后的下一次触发时间；禁用状态不给下次时间（调度器不扫它）。 */
    private OffsetDateTime computeNextFireTime(Trigger trigger) {
        if (!Boolean.TRUE.equals(trigger.getEnabled())) {
            return null;
        }
        ZoneId zone = ZoneId.of(trigger.getTimezone());
        OffsetDateTime cursor = OffsetDateTime.now(zone);
        if (trigger.getCronExpression() != null && !trigger.getCronExpression().isBlank()) {
            return CronExpression.parse(trigger.getCronExpression()).next(cursor);
        }
        if (trigger.getPeriodSeconds() != null) {
            return cursor.plusSeconds(trigger.getPeriodSeconds());
        }
        return null; // MANUAL：不自动触发
    }

    private String effectiveRangeJson(SaveTriggerRequest request) {
        boolean hasStart = request.getEffectiveStart() != null && !request.getEffectiveStart().isBlank();
        boolean hasEnd = request.getEffectiveEnd() != null && !request.getEffectiveEnd().isBlank();
        if (!hasStart && !hasEnd) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        if (hasStart) {
            sb.append("\"start\":\"").append(request.getEffectiveStart().trim()).append('"');
        }
        if (hasEnd) {
            if (hasStart) {
                sb.append(',');
            }
            sb.append("\"end\":\"").append(request.getEffectiveEnd().trim()).append('"');
        }
        return sb.append('}').toString();
    }

    private TriggerVO toVO(Trigger trigger, Workflow workflow) {
        TriggerVO vo = converter.toVO(trigger);
        vo.setWorkflowId(workflow.getWorkflowId());
        Map<String, String> range = fromJson(trigger.getEffectiveRange(),
                new TypeReference<>() { });
        if (range != null) {
            vo.setEffectiveStart(range.get("start"));
            vo.setEffectiveEnd(range.get("end"));
        }
        vo.setRunParams(fromJson(trigger.getRunParams(), new TypeReference<>() { }));
        return vo;
    }

    private ZoneId resolveZone(String timezone) {
        return ZoneId.of(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim());
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
        return value.trim();
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String toJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参数无法序列化为 JSON: " + e.getOriginalMessage());
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // 库里已有数据坏掉属于 500 而不是 400：不让历史脏数据把详情页打死
            throw new IllegalStateException("JSONB 字段解析失败: " + e.getOriginalMessage(), e);
        }
    }
}
