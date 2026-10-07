package com.flowops.modules.asset.service;

import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.asset.ExecutorNodeMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.modules.asset.converter.ExecutorNodeConverterImpl;
import com.flowops.modules.asset.dto.NodeTestResultVO;
import com.flowops.modules.asset.dto.SaveExecutorNodeRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行节点域单测（docs/05 §3.3 / docs/07 §4.2）：
 * 42205 禁用闸门、心跳字段不可人工改写、凭据引用校验、连通性测试的明文纪律。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutorNodeServiceTest {

    private static final String TEST_KEY = "unit-test-master-key";

    @Mock private ExecutorNodeMapper nodeMapper;
    @Mock private CredentialMapper credentialMapper;
    @Mock private ClusterService clusterService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ExecutorClient executorClient;

    private ExecutorNodeService service;
    private SecretCryptoService crypto;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(anyString())).thenReturn(3L);
        crypto = new SecretCryptoService(TEST_KEY);
        service = new ExecutorNodeService(nodeMapper, credentialMapper, clusterService, redis,
                new ExecutorNodeConverterImpl(), new ScopeGuard(), crypto, executorClient);
    }

    private ExecutorNode node() {
        ExecutorNode n = new ExecutorNode();
        n.setId(9L);
        n.setExecutorNodeId("EN-20261007-0001");
        n.setExecutorNodeName("node-a");
        n.setClusterId(1L);
        n.setIp("10.0.0.11");
        n.setOsType("LINUX");
        n.setConnectType("SSH");
        n.setEnabled(true);
        n.setOnlineStatus("ONLINE");
        n.setCredentialRefId(5L);
        return n;
    }

    private Cluster cluster() {
        Cluster c = new Cluster();
        c.setId(1L);
        c.setClusterId("CL-20261007-0001");
        c.setClusterName("生产集群A");
        return c;
    }

    // ── 42205 禁用闸门 ──────────────────────────────────────

    @Test
    void 禁用_节点上有运行中步骤_42205_且不落库() {
        when(nodeMapper.selectOne(any())).thenReturn(node());
        when(nodeMapper.countActiveStepsOnNode(1L, "10.0.0.11")).thenReturn(2L);

        assertThatThrownBy(() -> service.setEnabled("EN-20261007-0001", false))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42205);

        verify(nodeMapper, never()).updateById(any(ExecutorNode.class));
    }

    @Test
    void 禁用_节点空闲_成功落库() {
        when(nodeMapper.selectOne(any())).thenReturn(node());
        when(nodeMapper.countActiveStepsOnNode(1L, "10.0.0.11")).thenReturn(0L);
        when(clusterService.findById(1L)).thenReturn(cluster());

        service.setEnabled("EN-20261007-0001", false);

        ArgumentCaptor<ExecutorNode> captor = ArgumentCaptor.forClass(ExecutorNode.class);
        verify(nodeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getEnabled()).isFalse();
    }

    @Test
    void 禁用_已经是禁用态_幂等_不再落库() {
        ExecutorNode disabled = node();
        disabled.setEnabled(false);
        when(nodeMapper.selectOne(any())).thenReturn(disabled);
        when(clusterService.findById(1L)).thenReturn(cluster());

        service.setEnabled("EN-20261007-0001", false);

        verify(nodeMapper, never()).updateById(any(ExecutorNode.class));
        verify(nodeMapper, never()).countActiveStepsOnNode(any(), anyString());
    }

    @Test
    void 删除_节点被占用_42205() {
        when(nodeMapper.selectOne(any())).thenReturn(node());
        when(nodeMapper.countActiveStepsOnNode(1L, "10.0.0.11")).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("EN-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42205);
        verify(nodeMapper, never()).softDelete(any());
    }

    // ── 创建 ────────────────────────────────────────────────

    @Test
    void 创建_心跳字段由系统给初值_UNKNOWN而非OFFLINE() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ExecutorNode> captor = ArgumentCaptor.forClass(ExecutorNode.class);
        when(nodeMapper.insert(captor.capture())).thenReturn(1);

        SaveExecutorNodeRequest request = new SaveExecutorNodeRequest();
        request.setExecutorNodeName("node-a");
        request.setIp("10.0.0.11");
        request.setOsType("LINUX");
        service.create("CL-20261007-0001", request);

        ExecutorNode saved = captor.getValue();
        assertThat(saved.getExecutorNodeId()).startsWith("EN-");
        assertThat(saved.getOnlineStatus()).isEqualTo("UNKNOWN");
        assertThat(saved.getHeartbeatMissCount()).isZero();
        assertThat(saved.getConnectType()).isEqualTo("SSH");     // 缺省连接方式
        assertThat(saved.getTags()).isEmpty();
    }

    @Test
    void 创建_引用不存在的凭据业务编号_40400() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        when(credentialMapper.selectOne(any())).thenReturn(null);   // CR-xxxx 查不到

        SaveExecutorNodeRequest request = new SaveExecutorNodeRequest();
        request.setExecutorNodeName("node-a");
        request.setIp("10.0.0.11");
        request.setOsType("LINUX");
        request.setCredentialId("CR-20261007-0001");

        assertThatThrownBy(() -> service.create("CL-20261007-0001", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
        verify(nodeMapper, never()).insert(any(ExecutorNode.class));
    }

    @Test
    void 创建_凭据业务编号被解析成内部主键落库_出参回填业务编号() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        Credential credential = new Credential();
        credential.setId(5L);
        credential.setCredentialId("CR-20261007-0001");
        credential.setCredentialName("生产 SSH");
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        when(credentialMapper.selectById(5L)).thenReturn(credential);
        ArgumentCaptor<ExecutorNode> captor = ArgumentCaptor.forClass(ExecutorNode.class);
        when(nodeMapper.insert(captor.capture())).thenReturn(1);

        SaveExecutorNodeRequest request = new SaveExecutorNodeRequest();
        request.setExecutorNodeName("node-a");
        request.setIp("10.0.0.11");
        request.setOsType("LINUX");
        request.setCredentialId("CR-20261007-0001");
        var vo = service.create("CL-20261007-0001", request);

        // 落库存内部主键（外键语义），出网只给业务编号（内部 id 不出网）
        assertThat(captor.getValue().getCredentialRefId()).isEqualTo(5L);
        assertThat(vo.getCredentialId()).isEqualTo("CR-20261007-0001");
        assertThat(vo.getCredentialName()).isEqualTo("生产 SSH");
    }

    @Test
    void 创建_同集群IP重复_拒绝() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(nodeMapper.selectCount(any())).thenReturn(1L);

        SaveExecutorNodeRequest request = new SaveExecutorNodeRequest();
        request.setExecutorNodeName("node-a");
        request.setIp("10.0.0.11");
        request.setOsType("LINUX");

        assertThatThrownBy(() -> service.create("CL-20261007-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IP 已存在");
    }

    // ── 连通性测试 ──────────────────────────────────────────

    @Test
    void 连通性测试_未绑定凭据_42200() {
        ExecutorNode withoutCredential = node();
        withoutCredential.setCredentialRefId(null);
        when(nodeMapper.selectOne(any())).thenReturn(withoutCredential);

        assertThatThrownBy(() -> service.testConnectivity("EN-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42200);
    }

    @Test
    void 连通性测试_解密凭据后真实握手_结果不含明文() {
        when(nodeMapper.selectOne(any())).thenReturn(node());
        Credential credential = new Credential();
        credential.setId(5L);
        credential.setCredentialName("生产 SSH");
        credential.setUsername("flowops");
        credential.setSecretEncrypted(crypto.encrypt("-----BEGIN OPENSSH PRIVATE KEY-----x"));
        when(credentialMapper.selectById(5L)).thenReturn(credential);
        when(executorClient.testConnection(eq("10.0.0.11"), eq("flowops"), anyString())).thenReturn(true);

        NodeTestResultVO result = service.testConnectivity("EN-20261007-0001");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("成功");
        // 出参结构里根本没有 secret 字段可放明文（record 只有 success/message/elapsedMs）
        assertThat(result.toString()).doesNotContain("BEGIN OPENSSH");
    }

    @Test
    void 连通性测试_握手失败_返回false而不是抛异常() {
        when(nodeMapper.selectOne(any())).thenReturn(node());
        Credential credential = new Credential();
        credential.setId(5L);
        credential.setUsername("flowops");
        credential.setSecretEncrypted(crypto.encrypt("password-123"));
        when(credentialMapper.selectById(5L)).thenReturn(credential);
        when(executorClient.testConnection(anyString(), anyString(), anyString())).thenReturn(false);

        NodeTestResultVO result = service.testConnectivity("EN-20261007-0001");

        // 连不通是"测试结果"而不是"接口错误"：UI 要展示失败详情而不是弹错误码
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("失败");
    }
}
