package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.Trigger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 触发器 Mapper（docs/05 §3.4 {@code trigger}）。
 *
 * <p><b>表名是 PG 保留字</b>：MP 生成的 CRUD 由 {@code @TableName("\"trigger\"")}
 * 带引号；本接口的自定义 SQL（XML）也必须写 {@code "trigger"}。</p>
 */
@Mapper
public interface TriggerMapper extends BaseMapper<Trigger> {

    /**
     * 软删触发器。
     *
     * <p>用显式 XML 而不是 {@code updateById}：MyBatis-Plus 的 {@code updateById}
     * 会静默剔除逻辑删除列（软删失败成"什么都没改"），资产域四张表踩过同一坑。</p>
     */
    int softDelete(@Param("id") Long id);

    /** 停用某工作流下的全部触发器（工作流停用联动，docs/07 §6.4"触发器自动置 enabled=false"）。 */
    int disableByWorkflowId(@Param("workflowId") Long workflowId);
}
