package com.flowops.modules.alert.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.entity.alert.AlertRecord;
import com.flowops.domain.entity.alert.AlertRule;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.alert.AlertRecordMapper;
import com.flowops.domain.mapper.alert.AlertRuleMapper;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.modules.alert.dto.AlertRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 告警记录服务（M4-S4，CONTRACT §9 GET /alert-history；S5 工作台 latest-alerts 复用）。
 */
@Service
@RequiredArgsConstructor
public class AlertRecordService {

    private final AlertRecordMapper recordMapper;
    private final AlertRuleMapper ruleMapper;
    private final ProjectMapper projectMapper;
    private final ClusterMapper clusterMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Page<AlertRecordVO> history(String eventType, OffsetDateTime from, OffsetDateTime to,
                                       long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 200);
        String type = eventType == null || eventType.isBlank() ? null : eventType;
        List<AlertRecord> records = recordMapper.pageHistory(
                type, from, to, (current - 1) * pageSize, (int) pageSize);
        long total = recordMapper.countHistory(type, from, to);
        Page<AlertRecordVO> result = new Page<>(current, pageSize, total);
        result.setRecords(records.stream().map(this::toVO).toList());
        return result;
    }

    /** 最新告警（S5 工作台 GET /dashboard/latest-alerts，≤ limit 条）。 */
    public List<AlertRecordVO> latest(int limit) {
        return recordMapper.pageHistory(null, null, null, 0, Math.min(Math.max(1, limit), 50))
                .stream().map(this::toVO).toList();
    }

    /** 今日告警条数（S5 指标卡 alerts；口径 O-69）。 */
    public long alertsToday() {
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        return recordMapper.countHistory(null, todayStart, null);
    }

    private AlertRecordVO toVO(AlertRecord record) {
        AlertRecordVO vo = new AlertRecordVO();
        vo.setAlertId(record.getAlertId());
        // 规则业务编号翻译（rule_id 删除后 SET NULL → null 合法）
        if (record.getRuleId() != null) {
            AlertRule rule = ruleMapper.selectById(record.getRuleId());
            vo.setRuleId(rule == null ? null : rule.getRuleId());
        }
        vo.setEventType(record.getEventType());
        vo.setLevel(record.getLevel());
        vo.setTargetType(record.getTargetType());
        vo.setTargetId(record.getTargetId());
        vo.setTargetName(record.getTargetName());
        // D-27 出站翻译：项目/集群出业务编号，内部 bigint 主键绝不出网
        vo.setProjectId(codeOfProject(record.getProjectId()));
        vo.setClusterId(codeOfCluster(record.getClusterId()));
        vo.setContent(record.getContent());
        vo.setChannels(record.getChannels() == null ? List.of() : List.of(record.getChannels()));
        vo.setDeliverResult(parseDeliverResult(record.getDeliverResult()));
        vo.setResult(record.getResult());
        vo.setFiredAt(record.getFiredAt());
        return vo;
    }

    private String codeOfProject(Long rowId) {
        if (rowId == null) {
            return null;
        }
        Project p = projectMapper.selectById(rowId);
        return p == null ? null : p.getProjectId();
    }

    private String codeOfCluster(Long rowId) {
        if (rowId == null) {
            return null;
        }
        Cluster c = clusterMapper.selectById(rowId);
        return c == null ? null : c.getClusterId();
    }

    private List<Map<String, Object>> parseDeliverResult(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
