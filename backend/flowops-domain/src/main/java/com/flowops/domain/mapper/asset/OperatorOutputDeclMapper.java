package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.OperatorOutputDecl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 算子输出声明 Mapper（docs/05 §3.3 operator_output_decl，R10）。
 *
 * <p>与 {@link OperatorParamDefMapper} 同理：从属快照表，替换式维护，不做软删。</p>
 */
@Mapper
public interface OperatorOutputDeclMapper extends BaseMapper<OperatorOutputDecl> {

    int deleteByVersionId(@Param("operatorVersionId") Long operatorVersionId);

    List<OperatorOutputDecl> listByVersionId(@Param("operatorVersionId") Long operatorVersionId);

    /** 批量取多个版本的输出声明（DAG 规则 4 的"变量是否存在"判定，理由见参数定义侧同名方法）。 */
    List<OperatorOutputDecl> listByVersionIds(@Param("operatorVersionIds") Collection<Long> operatorVersionIds);
}
