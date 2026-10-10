package com.flowops.domain.mapper.alert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.alert.AlertRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/** 告警记录 Mapper（docs/05 §3.7 alert_record；查询面见 XML）。 */
@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {

    /** 同事件 + 同目标最近一次记录（抑制窗口判定，idx_alert_suppress）。 */
    AlertRecord findLatestFired(@Param("eventType") String eventType, @Param("targetId") Long targetId);

    /** 触发历史（eventType/from/to 可选过滤，fired_at 倒序；多列 SELECT 起别名限定每列）。 */
    List<AlertRecord> pageHistory(@Param("eventType") String eventType,
                                  @Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to,
                                  @Param("offset") long offset,
                                  @Param("limit") int limit);

    long countHistory(@Param("eventType") String eventType,
                      @Param("from") OffsetDateTime from,
                      @Param("to") OffsetDateTime to);
}
