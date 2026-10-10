package com.flowops.modules.alert.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.alert.AlertRule;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.alert.AlertRuleMapper;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.modules.alert.dto.AlertRuleUpsertRequest;
import com.flowops.modules.alert.dto.AlertRuleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警规则服务（M4-S4，CONTRACT §9）。
 *
 * <p><b>两处"以 DDL 为准"的校验镜像</b>（单测钉字面量，DDL 变更时同步）：</p>
 * <ul>
 *   <li>event_type：DDL CHECK 9 值 —— CONTRACT §9 只列 5 个，冲突已勘定以 DDL + V5 seed
 *       为准（README-M4 §4 开头注记），多出来的 4 个是 V1.1 事件（QUEUE_BACKLOG 等有
 *       CHECK 无接入，本切片只接 NODE_OFFLINE，见 O-67）；</li>
 *   <li>channels：DDL CHECK 4 渠道类型（CONTRACT 漏了 DINGTALK）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    /** DDL CHECK event_type 9 值的 Java 镜像（勿与 CONTRACT §9 的 5 值清单混淆） */
    static final Set<String> EVENT_TYPES = Set.of(
            "TASK_FAILED", "TASK_TIMEOUT", "STEP_RETRY_EXHAUSTED", "NODE_OFFLINE",
            "CONCURRENCY_LIMIT", "QUEUE_BACKLOG", "RESOURCE_HIGH", "CREDENTIAL_EXPIRED",
            "TRIGGER_FAIL");

    /** DDL CHECK channel_type 4 值的 Java 镜像 */
    static final Set<String> CHANNEL_TYPES = Set.of("EMAIL", "WECOM", "WEBHOOK", "DINGTALK");

    private final AlertRuleMapper ruleMapper;
    private final ProjectMapper projectMapper;
    private final ClusterMapper clusterMapper;
    private final IdGen idGen;
    private final ObjectMapper objectMapper;

    public List<AlertRuleVO> list(String eventType, Boolean enabled) {
        List<AlertRule> rules = ruleMapper.selectList(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getDeleted, false)
                .eq(eventType != null && !eventType.isBlank(), AlertRule::getEventType, eventType)
                .eq(enabled != null, AlertRule::getEnabled, enabled)
                .orderByDesc(AlertRule::getCreatedAt));
        return rules.stream().map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public AlertRuleVO create(AlertRuleUpsertRequest request) {
        AlertRule rule = new AlertRule();
        rule.setRuleId(idGen.next("AR", "ar"));
        applyRequest(rule, request);
        rule.setEnabled(true);
        rule.setDeleted(false);
        rule.setCreator(UserContext.get() != null ? UserContext.get().getUsername() : "system");
        ruleMapper.insert(rule);
        log.info("告警规则已创建 rule={} event={}", rule.getRuleId(), rule.getEventType());
        return toVO(rule);
    }

    @Transactional(rollbackFor = Exception.class)
    public AlertRuleVO update(String ruleId, AlertRuleUpsertRequest request) {
        AlertRule rule = requireRule(ruleId);
        applyRequest(rule, request);
        ruleMapper.updateById(rule);   // version 乐观锁（OptimisticLockerInnerInterceptor）
        return toVO(requireRule(ruleId));
    }

    @Transactional(rollbackFor = Exception.class)
    public AlertRuleVO toggleEnabled(String ruleId, boolean enabled) {
        AlertRule rule = requireRule(ruleId);
        String by = UserContext.get() != null ? UserContext.get().getUsername() : "system";
        ruleMapper.toggleEnabled(rule.getId(), enabled, by);   // 0 行 = 已是目标状态（幂等成功）
        return toVO(requireRule(ruleId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String ruleId) {
        AlertRule rule = requireRule(ruleId);
        ruleMapper.softDelete(rule.getId(),
                UserContext.get() != null ? UserContext.get().getUsername() : "system");
        log.info("告警规则已删除 rule={}", ruleId);
    }

    // ── 内部 ────────────────────────────────────────────────

    private AlertRule requireRule(String ruleId) {
        AlertRule rule = ruleMapper.selectOne(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getRuleId, ruleId)
                .eq(AlertRule::getDeleted, false));
        if (rule == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "告警规则不存在: " + ruleId,
                    Map.of("resource_type", "ALERT_RULE", "resource_id", ruleId));
        }
        return rule;
    }

    private void applyRequest(AlertRule rule, AlertRuleUpsertRequest request) {
        if (!EVENT_TYPES.contains(request.getEventType())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED,
                    "未知的事件类型: " + request.getEventType(),
                    Map.of("allowed", EVENT_TYPES));
        }
        List<String> channels = request.getChannels() == null ? List.of() : request.getChannels();
        if (!CHANNEL_TYPES.containsAll(channels)) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED,
                    "未知的渠道类型: " + channels,
                    Map.of("allowed", CHANNEL_TYPES));
        }
        rule.setRuleName(request.getRuleName());
        rule.setEventType(request.getEventType());
        rule.setCondition(toConditionJson(request.getCondition()));
        rule.setChannels(channels.toArray(new String[0]));
        rule.setReceivers(toJson(request.getReceivers() == null ? List.of() : request.getReceivers()));
        int window = request.getSuppressWindowMinutes() == null ? 30 : request.getSuppressWindowMinutes();
        if (window < 1) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "抑制窗口必须 ≥ 1 分钟",
                    Map.of("rule", "SUPPRESS_WINDOW_MIN"));
        }
        rule.setSuppressWindowMinutes(window);
        rule.setProjectId(resolveProjectId(request.getProjectId()));
        rule.setClusterId(resolveClusterId(request.getClusterId()));
    }

    /** 条件 JSON（42241：不是合法 JSON 对象则拒 —— docs/07 §4 的 ALERT_CONDITION_INVALID）。 */
    private String toConditionJson(Map<String, Object> condition) {
        Map<String, Object> value = condition == null ? Map.of() : condition;
        try {
            String json = objectMapper.writeValueAsString(value);
            if (!json.startsWith("{")) {
                throw new IllegalStateException("condition 必须是 JSON 对象");
            }
            return json;
        } catch (Exception e) {
            throw new BizException(ErrorCode.ALERT_CONDITION_INVALID,
                    "告警条件 JSON 不合法: " + e.getMessage());
        }
    }

    private String toJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Long resolveProjectId(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return null;
        }
        Project p = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, projectCode));
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在: " + projectCode,
                    Map.of("resource_type", "PROJECT", "resource_id", projectCode));
        }
        return p.getId();
    }

    private Long resolveClusterId(String clusterCode) {
        if (clusterCode == null || clusterCode.isBlank()) {
            return null;
        }
        Cluster c = clusterMapper.selectOne(Wrappers.<Cluster>lambdaQuery()
                .eq(Cluster::getClusterId, clusterCode));
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "集群不存在: " + clusterCode,
                    Map.of("resource_type", "CLUSTER", "resource_id", clusterCode));
        }
        return c.getId();
    }

    private AlertRuleVO toVO(AlertRule rule) {
        AlertRuleVO vo = new AlertRuleVO();
        vo.setRuleId(rule.getRuleId());
        vo.setRuleName(rule.getRuleName());
        vo.setEventType(rule.getEventType());
        vo.setCondition(fromJson(rule.getCondition()));
        vo.setChannels(rule.getChannels() == null ? List.of() : List.of(rule.getChannels()));
        vo.setReceivers(fromJsonList(rule.getReceivers()));
        vo.setSuppressWindowMinutes(rule.getSuppressWindowMinutes());
        vo.setProjectId(projectCodeOf(rule.getProjectId()));
        vo.setClusterId(clusterCodeOf(rule.getClusterId()));
        vo.setEnabled(rule.getEnabled());
        vo.setCreator(rule.getCreator());
        vo.setCreatedAt(rule.getCreatedAt());
        vo.setUpdatedAt(rule.getUpdatedAt());
        return vo;
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** D-27 出站翻译：内部行 id → 业务编号（查不到按 null，不出内部主键）。 */
    private String projectCodeOf(Long rowId) {
        if (rowId == null) {
            return null;
        }
        Project p = projectMapper.selectById(rowId);
        return p == null ? null : p.getProjectId();
    }

    private String clusterCodeOf(Long rowId) {
        if (rowId == null) {
            return null;
        }
        Cluster c = clusterMapper.selectById(rowId);
        return c == null ? null : c.getClusterId();
    }
}
