package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.OperatorParamDef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 算子参数定义 Mapper（docs/05 §3.3 operator_param_def，R9）。
 *
 * <p>本表随版本整体生命周期（新增/替换/删除），故只有"按版本删除 + 按版本列出"两个
 * 自定义动作，没有软删与 CAS —— 与 DDL 不带 {@code version}/{@code deleted} 一致。</p>
 */
@Mapper
public interface OperatorParamDefMapper extends BaseMapper<OperatorParamDef> {

    /** 替换式更新：先删后插（草稿版本编辑时用），保证 seq/key 唯一性不会中途冲突。 */
    int deleteByVersionId(@Param("operatorVersionId") Long operatorVersionId);

    /** 详情页与变量校验都要按 seq 稳定排序。 */
    List<OperatorParamDef> listByVersionId(@Param("operatorVersionId") Long operatorVersionId);
}
