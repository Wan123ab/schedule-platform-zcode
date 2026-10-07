package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.modules.asset.converter.CredentialConverter;
import com.flowops.modules.asset.dto.CredentialVO;
import com.flowops.modules.asset.dto.SaveCredentialRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * 凭据服务（docs/03 §4.1 / docs/07 §6.2）—— 加密存储、指纹展示、轮换、引用计数禁删。
 *
 * <p><b>明文生命周期</b>：请求体 → {@link SecretCryptoService#encrypt} → 密文入库；
 * 明文只在本方法栈内存在一个语句的时长，之后无任何引用（Java 无法真正擦除 String，
 * 靠"不存储、不日志、不返回"三不纪律兜底 —— 请求体日志过滤器已对 secret 打码）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final CredentialMapper credentialMapper;
    private final ProjectMapper projectMapper;
    private final SecretCryptoService crypto;
    private final IdGen idGen;
    private final CredentialConverter converter;

    // ── 查询 ────────────────────────────────────────────────

    public com.baomidou.mybatisplus.core.metadata.IPage<CredentialVO> page(long page, long size, String keyword) {
        Page<Credential> result = credentialMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Credential>lambdaQuery()
                        .eq(Credential::getDeleted, false)
                        .like(keyword != null && !keyword.isBlank(), Credential::getCredentialName, keyword)
                        .orderByDesc(Credential::getCreatedAt));
        return result.convert(this::toVO);
    }

    public CredentialVO get(String credentialId) {
        Credential credential = requireByBusinessId(credentialId);
        return toVO(credential);
    }

    // ── 写操作 ──────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public CredentialVO create(SaveCredentialRequest request) {
        if (request.getSecret() == null || request.getSecret().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "secret 必填");
        }
        Credential credential = new Credential();
        credential.setCredentialId(nextCredentialId());
        applyRequest(credential, request);
        credential.setStatus("VALID");
        credential.setRefCount(0);
        credential.setCreator(currentUsername());
        credentialMapper.insert(credential);
        log.info("凭据已创建 credential={} type={}（明文已加密入库，不落日志）",
                credential.getCredentialId(), credential.getCredentialType());
        return toVO(credential);
    }

    @Transactional(rollbackFor = Exception.class)
    public CredentialVO update(String credentialId, SaveCredentialRequest request) {
        Credential credential = requireByBusinessId(credentialId);
        applyRequest(credential, request);
        credentialMapper.updateById(credential);
        return toVO(credential);
    }

    /** 轮换（必审动作 ROTATE_CREDENTIAL）：换密文 + 刷新指纹与时间戳；引用它的节点无需感知。 */
    @Transactional(rollbackFor = Exception.class)
    public CredentialVO rotate(String credentialId, String newSecret) {
        if (newSecret == null || newSecret.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新 secret 必填");
        }
        Credential credential = requireByBusinessId(credentialId);
        credential.setSecretEncrypted(crypto.encrypt(newSecret));
        credential.setSecretFingerprint(fingerprint(newSecret));
        credential.setLastRotatedAt(OffsetDateTime.now());
        credentialMapper.updateById(credential);
        log.info("凭据已轮换 credential={}（引用节点下次执行自动使用新凭据）", credentialId);
        return toVO(credential);
    }

    /** 删除闸门（PRD §7.2 / docs/05 §6.3）：实时 COUNT > 0 → 42202；ref_count 列只做展示。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String credentialId) {
        Credential credential = requireByBusinessId(credentialId);
        long references = credentialMapper.countReferences(credential.getId());
        if (references > 0) {
            throw new BizException(ErrorCode.CREDENTIAL_REFERENCED, "凭据被引用，禁止删除",
                    java.util.Map.of("ref_count", references));
        }
        if (credential.getRefCount() != null && credential.getRefCount() > 0) {
            // 实时 COUNT=0 但快照列 >0：数据异常信号（对账兜底），照删并告警
            log.warn("凭据 ref_count 快照与实时 COUNT 不一致 credential={} snapshot={}",
                    credentialId, credential.getRefCount());
        }
        // 走显式 XML 软删除：MP 的 updateById 会把逻辑删除列从 SET 中剔除（M2 实测，见 Mapper 注释）
        credentialMapper.softDelete(credential.getId());
    }

    // ── 内部 ────────────────────────────────────────────────

    private void applyRequest(Credential credential, SaveCredentialRequest request) {
        credential.setCredentialName(request.getCredentialName());
        credential.setCredentialType(request.getCredentialType());
        credential.setUsername(request.getUsername());
        credential.setProjectId(resolveProjectId(request.getProjectId()));
        credential.setExpireAt(request.getExpireAt());
        credential.setDescription(request.getDescription());
        if (request.getSecret() != null && !request.getSecret().isBlank()) {
            credential.setSecretEncrypted(crypto.encrypt(request.getSecret()));
            credential.setSecretFingerprint(fingerprint(request.getSecret()));
        }
    }

    /**
     * 归属项目解析：入参是**业务编号** {@code PRJ-xxxx}（内部 Long 主键不出网），
     * 这里翻译成内部主键；留空表示平台级凭据（{@code project_id IS NULL}）。
     *
     * <p>归属一个不存在的项目会让凭据变成"孤儿"，故在写路径直接 40400。</p>
     */
    private Long resolveProjectId(String projectBusinessId) {
        if (projectBusinessId == null || projectBusinessId.isBlank()) {
            return null;
        }
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, projectBusinessId).eq(Project::getDeleted, false));
        if (project == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "归属项目不存在: " + projectBusinessId,
                    java.util.Map.of("resource_type", "PROJECT", "resource_id", projectBusinessId));
        }
        return project.getId();
    }

    /** 出参补齐：内部主键 → 业务编号 + 跨表项目名（converter 已 ignore 这两个字段）。 */
    private CredentialVO toVO(Credential credential) {
        CredentialVO vo = converter.toVO(credential);
        if (credential.getProjectId() != null) {
            Project project = projectMapper.selectById(credential.getProjectId());
            if (project != null) {
                vo.setProjectId(project.getProjectId());
                vo.setProjectName(project.getProjectName());
            }
        }
        return vo;
    }

    /** SHA-256(secret) 全量 hex 入库；展示侧取后 4 位（docs/03 §4.1）。 */
    static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Credential requireByBusinessId(String credentialId) {
        Credential credential = credentialMapper.selectOne(Wrappers.<Credential>lambdaQuery()
                .eq(Credential::getCredentialId, credentialId).eq(Credential::getDeleted, false));
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "凭据不存在: " + credentialId);
        }
        return credential;
    }

    private String nextCredentialId() {
        return idGen.nextDated("CR", "cr");
    }

    private String currentUsername() {
        var ctx = UserContext.get();
        return ctx != null ? ctx.getUsername() : "system";
    }
}
