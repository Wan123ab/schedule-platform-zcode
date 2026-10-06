package com.flowops.domain.mapper.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.audit.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
