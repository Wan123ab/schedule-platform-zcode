package com.flowops.domain.mapper.alert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.alert.AlertChannel;
import org.apache.ibatis.annotations.Mapper;

/** 通知渠道 Mapper（docs/05 §3.7 alert_channel；channel_type UNIQUE，upsert 语义）。 */
@Mapper
public interface AlertChannelMapper extends BaseMapper<AlertChannel> {
}
