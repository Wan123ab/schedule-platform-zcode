package com.flowops.modules.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.FieldError;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorParamDef;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.executor.protocol.ExecuteResult;
import com.flowops.modules.asset.dto.DryRunRequest;
import com.flowops.modules.asset.service.OperatorDryRunService.DryRunPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 算子试运行服务单测（PRD §10.6）。
 *
 * <p><b>四类断言，按"错了会怎样"排序</b>：</p>
 * <ol>
 *   <li><b>不写任务表</b>：本类依赖里根本没有 Task/TaskStep/TaskLog 的 Mapper
 *       —— 这条由构造函数签名保证（编译期），下面不再用 mock 去"断言没调用"；</li>
 *   <li><b>不该连的时候绝不连</b>：校验失败、引用落空、节点不可用时，执行客户端
 *       与凭据解密都不该被触发（否则等于"参数没填好也 ssh 过去"）；</li>
 *   <li><b>敏感值不出网</b>：展示命令、参数快照、日志行、以及 plan 的 toString
 *       四个出口逐一验；</li>
 *   <li><b>与调度同款语义</b>：默认值合并、成功码判定、超时封顶。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorDryRunServiceTest {

    private static final String VERSION = "OPV-0002-02";
    private static final String NODE = "EN-0001";
    private static final String SECRET = "hunter2secret";

    @Mock private OperatorVersionService versionService;
    @Mock private OperatorService operatorService;
    @Mock private OperatorParamDefMapper paramDefMapper;
    @Mock private ExecutorNodeService nodeService;
    @Mock private CredentialMapper credentialMapper;
    @Mock private ExecutorClient executorClient;

    private final SecretCryptoService crypto = new SecretCryptoService("flowops-unit-test-master-key");

    private OperatorDryRunService service;

    @BeforeEach
    void setUp() {
        service = new OperatorDryRunService(versionService, operatorService, paramDefMapper, nodeService,
                credentialMapper, crypto, executorClient, new ObjectMapper());
        givenVersion(version());
        givenOperator();
        givenNode(node(true, "LINUX", 51L));
        givenCredential("flowops");
    }

    // ── 计划装配 ────────────────────────────────────────────

    @Test
    void 装配计划_入参覆盖默认值且按模板顺序展开() {
        givenParams(def("input_path", "TEXT", true, "/default/input", false, null),
                def("partitions", "NUMBER", false, "100", false, null));

        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of("input_path", "/data/orders"), null));

        assertThat(plan.displayCommand()).isEqualTo("run --input /data/orders");
        assertThat(plan.snapshotParams().keySet()).containsExactly("input_path", "partitions");
        assertThat(plan.snapshotParams()).containsEntry("partitions", "100");   // 未提供 → 取默认值
        assertThat(plan.dryRunId()).startsWith("DR-");
        assertThat(plan.nodeId()).isEqualTo(NODE);
        assertThat(plan.machineIp()).isEqualTo("10.0.0.1");
        assertThat(plan.timeoutSeconds()).isEqualTo(600);        // 未指定 → 平台上限
        assertThat(plan.successCodes()).containsExactly(0);
    }

    @Test
    void 装配计划_参数值里的引用按六层解析_并把真实凭据注入执行指令() {
        givenParams(def("input_path", "TEXT", true, null, false, null));

        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of("input_path", "/data/x"), null));

        assertThat(plan.command().getResolvedCommand()).isEqualTo("run --input /data/x");
        assertThat(plan.command().getUsername()).isEqualTo("flowops");
        assertThat(plan.command().getSecretMaterial()).isEqualTo(SECRET);   // 解密后的凭据只给执行侧
        assertThat(plan.command().getWorkDir()).isEqualTo("/opt/app");
    }

    @Test
    void plan的toString_不含命令与凭据_防止一次调试日志泄漏() {
        givenParams(def("input_path", "TEXT", true, null, false, null));

        String text = service.plan(VERSION, request(NODE, Map.of("input_path", "/data/x"), null)).toString();

        assertThat(text).contains("OPV-0002-02", "EN-0001")
                .doesNotContain("/data/x", SECRET, "run --input");
    }

    // ── 敏感值不出网 ────────────────────────────────────────

    @Test
    void 敏感参数_展示命令脱敏而执行命令保留真值() {
        givenVersion(versionWithCommand("run --password ${password}"));
        givenParams(def("password", "TEXT", true, SECRET, true, null));

        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of(), null));

        assertThat(plan.displayCommand()).isEqualTo("run --password ***");
        assertThat(plan.resolvedCommand()).isEqualTo("run --password " + SECRET);
        assertThat(plan.snapshotParams()).containsEntry("password", "***");
    }

    /** 敏感值直接字面量写在命令里（没走 ${} 注入）—— 快照渲染覆盖不到，只能按值替换。 */
    @Test
    void 敏感值字面量出现在命令里_同样被脱敏() {
        givenVersion(versionWithCommand("curl -H 'token: " + SECRET + "' https://api"));
        givenParams(def("password", "TEXT", false, SECRET, true, null));

        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of(), null));

        assertThat(plan.displayCommand()).isEqualTo("curl -H 'token: ***' https://api");
        assertThat(plan.displayCommand()).doesNotContain(SECRET);
    }

    @Test
    void 日志行里的敏感值在回调前被脱敏() {
        givenVersion(versionWithCommand("run --password ${password}"));
        givenParams(def("password", "TEXT", false, SECRET, true, null));
        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of(), null));
        givenExecuteEmits("password=" + SECRET + " ok");

        List<String[]> received = new ArrayList<>();
        service.run(plan, (stream, line) -> received.add(new String[]{stream, line}));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)[1]).isEqualTo("password=*** ok");
    }

    @Test
    void secret环境变量取值也纳入脱敏范围() {
        givenVersion(versionWithEnv("""
                [{"key":"API_TOKEN","value":"env-token-abcdef","secret":true},
                 {"key":"PLAIN","value":"visible","secret":false}]"""));
        givenParams(def("input_path", "TEXT", true, null, false, null));
        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of("input_path", "/x"), null));
        givenExecuteEmits("using env-token-abcdef and visible");

        List<String> lines = new ArrayList<>();
        service.run(plan, (stream, line) -> lines.add(line));

        assertThat(lines).containsExactly("using *** and visible");
    }

    // ── 建流之前的闸门（都不得触发执行）────────────────────

    @Test
    void 必填参数缺失_42210且带errors_且不连节点也不解密凭据() {
        givenParams(def("input_path", "TEXT", true, null, false, null));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少必填参数")
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getErrorCode().getCode()).isEqualTo(42210);
                    assertThat(errorsOf(biz)).extracting(FieldError::field).containsExactly("params.input_path");
                });
        verify(executorClient, never()).execute(any(), any());
        verify(credentialMapper, never()).selectById(anyLong());
    }

    @Test
    void 模板外参数_42210_拼写错误不会静默忽略() {
        givenParams(def("input_path", "TEXT", false, null, false, null));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of("input_paht", "/x"), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorsOf((BizException) e))
                        .extracting(FieldError::field).containsExactly("params.input_paht"));
    }

    /**
     * 启动命令引用了上游步骤输出：试运行没有上游，必须<b>明确报错</b>。
     * 若放行，下发到远端的就是带 {@code ${...}} 字面量的命令 —— 报错点跑到远端，
     * 而现场离成因已经很远。
     */
    @Test
    void 命令引用了不存在的变量_42214且绝不带着引用去执行() {
        givenVersion(versionWithCommand("run --in ${step.清洗.output.path}"));
        givenParams(def("input_path", "TEXT", false, null, false, null));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(42214));
        verify(executorClient, never()).execute(any(), any());
    }

    /** 参数值里嵌引用同样拦下：第 6 层不允许引用自己这一层（自引用只会产生循环）。 */
    @Test
    void 参数值里嵌了引用_42214() {
        givenParams(def("input_path", "TEXT", false, "${project.dataRoot}", false, null));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(42214));
    }

    @Test
    void 节点已禁用_42200且带rule() {
        givenNode(node(false, "LINUX", 51L));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(payloadOf((BizException) e)).containsEntry("rule", "NODE_DISABLED"));
    }

    @Test
    void 节点非Linux_42200且带rule() {
        givenNode(node(true, "WINDOWS", 51L));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(payloadOf((BizException) e))
                        .containsEntry("rule", "NODE_OS_UNSUPPORTED"));
    }

    @Test
    void 节点未绑凭据_42200且带rule() {
        givenNode(node(true, "LINUX", null));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(payloadOf((BizException) e))
                        .containsEntry("rule", "NODE_CREDENTIAL_REQUIRED"));
    }

    @Test
    void 节点绑定的凭据不存在_40400() {
        when(credentialMapper.selectById(51L)).thenReturn(null);

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(40400));
    }

    @Test
    void 启动命令为空_明确报错而不是下发空命令() {
        givenVersion(versionWithCommand("   "));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of(), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(payloadOf((BizException) e))
                        .containsEntry("rule", "DRYRUN_COMMAND_MISSING"));
    }

    // ── 超时与成功码 ────────────────────────────────────────

    @Test
    void 超时优先取请求值_其次版本默认值() {
        givenBasicParams();
        OperatorVersion v = version();
        v.setDefaultTimeoutSeconds(120);
        givenVersion(v);

        assertThat(service.plan(VERSION, request(NODE, Map.of(), null)).timeoutSeconds()).isEqualTo(120);
        assertThat(service.plan(VERSION, request(NODE, Map.of(), 45)).timeoutSeconds()).isEqualTo(45);
    }

    /** 版本默认值可能是按旧口径配的（例如 3600s），必须被平台上限封顶。 */
    @Test
    void 版本默认超时超过上限_封顶为600而不是拒绝() {
        givenBasicParams();
        OperatorVersion v = version();
        v.setDefaultTimeoutSeconds(3600);
        givenVersion(v);

        assertThat(service.plan(VERSION, request(NODE, Map.of(), null)).timeoutSeconds()).isEqualTo(600);
    }

    @Test
    void 成功码取版本配置_缺省为0() {
        givenBasicParams();
        OperatorVersion v = version();
        v.setSuccessCodes(new Integer[]{0, 1});
        givenVersion(v);

        assertThat(service.plan(VERSION, request(NODE, Map.of(), null)).successCodes()).containsExactly(0, 1);

        OperatorVersion noCodes = version();
        noCodes.setSuccessCodes(new Integer[0]);
        givenVersion(noCodes);
        assertThat(service.plan(VERSION, request(NODE, Map.of(), null)).successCodes()).containsExactly(0);
    }

    @Test
    void 成败判定_退出码必须在成功码集合内() {
        assertThat(OperatorDryRunService.isSuccess(0, List.of(0))).isTrue();
        assertThat(OperatorDryRunService.isSuccess(1, List.of(0))).isFalse();
        assertThat(OperatorDryRunService.isSuccess(1, List.of(0, 1))).isTrue();
        // null = 进程未能确认启动（连接/认证失败、超时强杀），永远不算成功
        assertThat(OperatorDryRunService.isSuccess(null, List.of(0))).isFalse();
    }

    @Test
    void 参数模板里的坏JSON候选项_退化为不做候选值校验而不是500() {
        givenVersion(versionWithCommand("echo hi"));
        givenParams(def("mode", "SINGLE", false, "fast", false, "{不是合法JSON"));

        DryRunPlan plan = service.plan(VERSION, request(NODE, Map.of("mode", "whatever"), null));

        assertThat(plan.snapshotParams()).containsEntry("mode", "whatever");
    }

    @Test
    void 参数候选值合法时_取值超出候选被拒() {
        givenParams(def("mode", "SINGLE", false, "fast", false, "[\"fast\",\"safe\"]"));

        assertThatThrownBy(() -> service.plan(VERSION, request(NODE, Map.of("mode", "turbo"), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorsOf((BizException) e))
                        .extracting(FieldError::field).containsExactly("params.mode"));
    }

    // ── 夹具 ────────────────────────────────────────────────

    private void givenVersion(OperatorVersion version) {
        when(versionService.requireVisible(VERSION)).thenReturn(version);
    }

    private void givenOperator() {
        Operator operator = new Operator();
        operator.setId(11L);
        operator.setOperatorId("OP-0002");
        operator.setOperatorName("数据清洗");
        when(operatorService.findById(11L)).thenReturn(operator);
    }

    private void givenNode(ExecutorNode node) {
        when(nodeService.requireVisible(NODE)).thenReturn(node);
    }

    private void givenCredential(String username) {
        Credential credential = new Credential();
        credential.setId(51L);
        credential.setCredentialId("CR-0001");
        credential.setUsername(username);
        credential.setSecretEncrypted(crypto.encrypt(SECRET));
        when(credentialMapper.selectById(51L)).thenReturn(credential);
    }

    private void givenParams(OperatorParamDef... defs) {
        when(paramDefMapper.listByVersionId(31L)).thenReturn(List.of(defs));
    }

    /** 默认命令引用了 {@code ${input_path}}，故凡是只关心其它断言的用例都先把它配齐。 */
    private void givenBasicParams() {
        givenParams(def("input_path", "TEXT", false, "/default/input", false, null));
    }

    /** 让执行客户端立刻回调一行，用于验证脱敏链路。 */
    private void givenExecuteEmits(String line) {
        when(executorClient.execute(any(), any())).thenAnswer(invocation -> {
            ExecutorClient.LineListener listener = invocation.getArgument(1);
            listener.onLine("stdout", line);
            return ExecuteResult.builder().exitCode(0).durationNanos(1_000_000L).build();
        });
    }

    private OperatorVersion version() {
        return versionWithCommand("run --input ${input_path}");
    }

    private OperatorVersion versionWithCommand(String command) {
        OperatorVersion version = new OperatorVersion();
        version.setId(31L);
        version.setVersionId(VERSION);
        version.setVersionNo("v2");
        version.setOperatorId(11L);
        version.setStartCommand(command);
        version.setWorkDir("/opt/app");
        version.setPublishStatus("PUBLISHED");
        version.setSuccessCodes(new Integer[]{0});
        version.setEnvVars("[]");
        return version;
    }

    private OperatorVersion versionWithEnv(String envJson) {
        OperatorVersion version = version();
        version.setEnvVars(envJson);
        return version;
    }

    private ExecutorNode node(boolean enabled, String osType, Long credentialRefId) {
        ExecutorNode node = new ExecutorNode();
        node.setId(41L);
        node.setExecutorNodeId(NODE);
        node.setExecutorNodeName("node-a");
        node.setClusterId(21L);
        node.setIp("10.0.0.1");
        node.setOsType(osType);
        node.setConnectType("SSH");
        node.setEnabled(enabled);
        node.setCredentialRefId(credentialRefId);
        return node;
    }

    private OperatorParamDef def(String key, String type, boolean required, String defaultValue,
                                 boolean sensitive, String optionsJson) {
        OperatorParamDef def = new OperatorParamDef();
        def.setOperatorVersionId(31L);
        def.setParamKey(key);
        def.setName(key);
        def.setParamType(type);
        def.setRequired(required);
        def.setDefaultValue(defaultValue);
        def.setRuntimeOverridable(true);
        def.setSensitive(sensitive);
        def.setOptions(optionsJson);
        return def;
    }

    private DryRunRequest request(String nodeId, Map<String, Object> params, Integer timeout) {
        DryRunRequest request = new DryRunRequest();
        request.setExecutorNodeId(nodeId);
        request.setParams(new LinkedHashMap<>(params));
        request.setTimeoutSeconds(timeout);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<FieldError> errorsOf(BizException e) {
        return (List<FieldError>) payloadOf(e).get("errors");
    }

    /** 异常 payload 是非泛型的 Object（随响应下发的附加数据），断言前统一收窄。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(BizException e) {
        return (Map<String, Object>) e.getPayload();
    }
}
