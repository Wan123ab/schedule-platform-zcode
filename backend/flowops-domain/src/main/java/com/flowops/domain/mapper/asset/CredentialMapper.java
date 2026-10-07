package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.Credential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 凭据 Mapper（docs/05 §3.3）。
 * ⚠️ secret_encrypted 仅允许凭据域单表投影读取，任何 join/列表查询禁止引用该列。
 */
@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {

    /**
     * 引用计数实时校验（docs/05 §6.3 删除判定的唯一权威）：
     * 当前引用方 = executor_node.credential_ref_id；后续新增引用方纳入 UNION。
     */
    long countReferences(@Param("credentialId") Long credentialId);

    /**
     * 软删除（显式 XML）。
     *
     * <p>⚠️ <b>不能用 {@code BaseMapper.updateById}</b>：MyBatis-Plus 生成 UPDATE_BY_ID 时
     * 会以 {@code sqlSet(logicDeleteField != null, ...)} 把逻辑删除列从 SET 子句里剔除，
     * 于是 {@code setDeleted(true) + updateById} 只自增 version、行仍在（静默失效）。
     * 该坑 M2 实测确认，见 README-M2「已知修复记录」。</p>
     */
    int softDelete(@Param("id") Long id);
}
