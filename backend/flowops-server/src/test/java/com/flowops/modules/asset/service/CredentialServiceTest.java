package com.flowops.modules.asset.service;

import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.modules.asset.dto.CredentialVO;
import com.flowops.modules.asset.converter.CredentialConverterImpl;
import com.flowops.modules.asset.dto.SaveCredentialRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 凭据域单测（docs/03 §4.1 / docs/07 §6.2）：加密入库、指纹掩码、明文永不出网、42202 禁删。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CredentialServiceTest {

    @Mock private CredentialMapper credentialMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private CredentialService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(any(String.class))).thenReturn(1L);
        // 真实加密服务（test-key）：验证加密-解密全链路而非 mock 掉被测核心
        service = new CredentialService(credentialMapper, projectMapper, new SecretCryptoService("test-key"), redis,
                new CredentialConverterImpl());   // MapStruct 生成物：让掩码/映射逻辑真实执行
    }

    private SaveCredentialRequest request(String secret) {
        var r = new SaveCredentialRequest();
        r.setCredentialName("生产节点 SSH");
        r.setCredentialType("SSH_KEY");
        r.setUsername("flowops");
        r.setSecret(secret);
        return r;
    }

    @Test
    void 创建_密文入库_指纹掩码_明文不出网() {
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        when(credentialMapper.insert(captor.capture())).thenReturn(1);

        CredentialVO vo = service.create(request("s3cret-mingwen"));

        Credential saved = captor.getValue();
        assertThat(saved.getSecretEncrypted()).doesNotContain("s3cret");              // 密文非明文
        assertThat(new SecretCryptoService("test-key").decrypt(saved.getSecretEncrypted()))
                .isEqualTo("s3cret-mingwen");                                         // 可解密还原
        assertThat(vo.getSecretFingerprint()).startsWith("****").hasSize(8);          // **** + 4 位
        assertThat(vo.toString()).doesNotContain("s3cret");                           // 出参不含明文
        assertThat(saved.getRefCount()).isZero();
    }

    @Test
    void 缺secret_拒绝创建() {
        assertThatThrownBy(() -> service.create(request(null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("secret 必填");
    }

    @Test
    void 项目级凭据_业务编号解析成内部主键_出参回填业务编号与项目名() {
        Project project = new Project();
        project.setId(7L);
        project.setProjectId("PRJ-20261007-0001");
        project.setProjectName("推荐系统");
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.selectById(7L)).thenReturn(project);
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        when(credentialMapper.insert(captor.capture())).thenReturn(1);

        SaveCredentialRequest r = request("s3cret");
        r.setProjectId("PRJ-20261007-0001");
        CredentialVO vo = service.create(r);

        assertThat(captor.getValue().getProjectId()).isEqualTo(7L);      // 落库存内部主键（外键语义）
        assertThat(vo.getProjectId()).isEqualTo("PRJ-20261007-0001");    // 出网只给业务编号
        assertThat(vo.getProjectName()).isEqualTo("推荐系统");
    }

    @Test
    void 项目级凭据_归属项目不存在_40400且不落库() {
        when(projectMapper.selectOne(any())).thenReturn(null);

        SaveCredentialRequest r = request("s3cret");
        r.setProjectId("PRJ-20990101-9999");

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
        verify(credentialMapper, never()).insert(any(Credential.class));
    }

    @Test
    void 平台级凭据_项目编号留空_project_id落NULL() {
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        when(credentialMapper.insert(captor.capture())).thenReturn(1);

        CredentialVO vo = service.create(request("s3cret"));

        assertThat(captor.getValue().getProjectId()).isNull();
        assertThat(vo.getProjectId()).isNull();
        assertThat(vo.getProjectName()).isNull();
    }

    @Test
    void 被节点引用_42202_禁止删除_实时COUNT为唯一权威() {
        Credential existing = new Credential();
        existing.setId(1L);
        existing.setCredentialId("CR-20261007-0001");
        existing.setRefCount(2);
        when(credentialMapper.selectOne(any())).thenReturn(existing);
        when(credentialMapper.countReferences(1L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete("CR-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42202);
        verify(credentialMapper, never()).softDelete(any(Long.class));   // 闸门在先，绝不落删除
    }

    @Test
    void 无引用_允许删除_即使快照计数不一致也照删() {
        Credential existing = new Credential();
        existing.setId(1L);
        existing.setCredentialId("CR-20261007-0001");
        existing.setRefCount(3);   // 快照说 3
        when(credentialMapper.selectOne(any())).thenReturn(existing);
        when(credentialMapper.countReferences(1L)).thenReturn(0L);   // 实时 0（权威）

        service.delete("CR-20261007-0001");

        // docs/05 §6.3：实时 COUNT 是唯一权威；且删除必须走显式 XML softDelete
        // （MP 的 updateById 会把逻辑删除列从 SET 剔除 → setDeleted(true) 静默失效，M2 实测）
        verify(credentialMapper).softDelete(1L);
    }
}
