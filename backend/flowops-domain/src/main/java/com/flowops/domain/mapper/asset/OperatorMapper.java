package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.dto.query.OperatorReferenceRow;
import com.flowops.domain.entity.asset.Operator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 算子 Mapper（docs/05 §3.3 operator）。
 *
 * <p>行级过滤由 {@code FlowopsDataPermissionHandler} 按 {@code operator.project_id} 注入
 * （已登记在 PROJECT_SCOPED_COLUMNS），本接口不手写数据范围条件。</p>
 */
@Mapper
public interface OperatorMapper extends BaseMapper<Operator> {

    /**
     * 统计"某算子的版本是否已被工作流步骤引用"（42211 删除闸门）。
     *
     * <p>用 {@code count(distinct ws.id)} 而非 {@code count(*)}：{@code workflow_step}
     * 与 {@code workflow_version} 是 N:1，join 后 {@code count(*)} 会把同一步骤按版本行
     * 重复计数，让闸门报出虚高的引用数 —— 提示信息里的数字应当可信。</p>
     */
    long countVersionReferences(@Param("operatorId") Long operatorId);

    /** 某版本被哪些工作流步骤引用（GET /operator-versions/{versionId}/references）。 */
    List<OperatorReferenceRow> findReferences(@Param("versionId") Long versionId);

    int softDelete(@Param("id") Long id);
}
