package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.OperatorVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 算子版本 Mapper（docs/05 §3.3 operator_version）。
 */
@Mapper
public interface OperatorVersionMapper extends BaseMapper<OperatorVersion> {

    /**
     * 取该算子已用过的最大版本序号（<b>含已软删行</b>，故不能走 MP 的 lambda 查询 —— 
     * 逻辑删除过滤会自动加上，而 {@code uk_ov_version_id} 是<b>全表</b>唯一索引）。
     *
     * <p>这条查询是"版本号不复用"的保证：软删 v3 之后新建的版本若是又算出 v3，
     * 版本编号 {@code OPV-xxxx-03} 就会与已删行撞唯一索引，报一个与用户操作
     * 毫无关联的 23505。返回 0 表示该算子还没有任何版本。</p>
     */
    int selectMaxVersionIndex(@Param("operatorId") Long operatorId);

    /** 同算子内是否已有该 checksum 的<b>未删除</b>版本（重复上传提示"该文件已上传为版本 vN"）。 */
    OperatorVersion findByChecksum(@Param("operatorId") Long operatorId, @Param("checksum") String checksum);

    int softDelete(@Param("id") Long id);

    /** 删除算子时连同其全部版本一起软删（42211 已保证无工作流引用，故无需逐版本判定）。 */
    int softDeleteByOperatorId(@Param("operatorId") Long operatorId);

    /** 清空该算子当前的默认版本标记（发布新默认版本前的必要动作，uk_ov_default 是唯一索引）。 */
    int clearDefaultFlag(@Param("operatorId") Long operatorId);
}
