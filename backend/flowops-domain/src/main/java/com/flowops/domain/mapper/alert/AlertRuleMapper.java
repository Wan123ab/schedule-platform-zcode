package com.flowops.domain.mapper.alert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.alert.AlertRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 告警规则 Mapper（docs/05 §3.7 alert_rule；删除走 XML 显式 softDelete）。 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {

    /** 逻辑删除（显式 XML，不走 updateById —— 静默剔除逻辑删除列的坑）。 */
    int softDelete(@Param("id") Long id, @Param("deletedBy") String deletedBy);

    /** 启停 CAS（状态变更一律 CAS；目标状态与当前相同 → 0 行，调用方按幂等成功处理）。 */
    int toggleEnabled(@Param("id") Long id, @Param("enabled") boolean enabled,
                      @Param("updatedBy") String updatedBy);
}
