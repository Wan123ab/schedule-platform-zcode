package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.Credential;
import com.flowops.modules.asset.dto.CredentialVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * 凭据转换器（docs/07 §8.3：MapStruct 编译期生成 + unmappedTargetPolicy=ERROR 防漏字段）。
 *
 * <p><b>安全红线</b>：source 实体的 secretEncrypted 没有 VO 对应属性 —— 编译期即锁定
 * "明文/密文不出现任何出参"（新增字段漏脱敏会编译失败，而不是静默泄漏）。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CredentialConverter {

    @Mapping(target = "secretFingerprint", source = "secretFingerprint", qualifiedByName = "maskFingerprint")
    @Mapping(target = "projectId", ignore = true)      // 内部主键 → 业务编号，Service 回填
    @Mapping(target = "projectName", ignore = true)    // 跨表字段，Service 回填
    CredentialVO toVO(Credential entity);

    /** 指纹仅后 4 位可见（****xxxx），docs/03 §4.1。 */
    @Named("maskFingerprint")
    default String maskFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.length() >= 4
                ? "****" + fingerprint.substring(fingerprint.length() - 4) : "****";
    }
}
