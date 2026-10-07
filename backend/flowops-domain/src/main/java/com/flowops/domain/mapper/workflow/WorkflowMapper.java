package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作流 Mapper（docs/05 §3.4 workflow）。
 *
 * <p>单表读写走 MP 的 {@link BaseMapper}；需要绕过 MP 的两处（软删、聚合查询）
 * 才写 XML —— 与资产域同一纪律（所有 SQL 在 XML，禁止注解 SQL / JdbcTemplate）。</p>
 */
@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {

    /**
     * 软删除。**不能**用 {@code updateById}：MP 会把逻辑删除列从 SET 子句里剔除，
     * 于是"软删"静默变成"什么都不做"（README-M2 已实测的字节码行为）。
     * 顺带做 CAS（{@code version + 1}）与 {@code updated_at}（XML 更新绕过
     * MetaObjectHandler，时间戳必须自己补）。
     */
    int softDelete(@Param("id") Long id);
}
