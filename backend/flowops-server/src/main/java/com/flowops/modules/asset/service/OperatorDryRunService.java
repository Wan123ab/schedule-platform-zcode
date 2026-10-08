package com.flowops.modules.asset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.api.FieldError;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.OrderedCollections;
import com.flowops.domain.entity.asset.Credential;
import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorParamDef;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.mapper.asset.CredentialMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.resolve.SecretMasker;
import com.flowops.domain.resolve.VariableChainResolver;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.executor.protocol.ExecuteCommand;
import com.flowops.executor.protocol.ExecuteResult;
import com.flowops.modules.asset.dto.DryRunRequest;
import com.flowops.modules.asset.dto.OperatorVersionParts;
import com.flowops.modules.asset.validator.DryRunPlanValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 算子试运行服务（PRD §10.6；docs/09 M3「选节点 + 实时日志 + 退出码」）。
 *
 * <p><b>核心约束：不生成正式任务与步骤实例记录</b>（PRD §10.6）。因此本类
 * <b>刻意不注入</b> TaskMapper / TaskStepMapper / TaskLogMapper —— 不是靠"记得别写"，
 * 而是让"写任务表"这件事在依赖上就不可达；试运行的输出只走 SSE 通道，不落 task_log，
 * 也不占任务编号与并发额度的任何一格。</p>
 *
 * <p><b>为什么 plan / run 拆成两步（这条是安全边界，不是风格）</b>：
 * {@code plan} 必须在<b>请求线程</b>上跑完，因为数据范围（{@code ScopeContext}）
 * 与当前用户（{@code UserContext}）都存在 ThreadLocal 里；{@code run} 在工作线程上
 * 执行 SSH，那条线程<b>看不到任何上下文</b> —— 若在它上面查库，行级过滤会静默失效
 * （scope 为空 → 不注入条件 → 越权读），与 README-M3 §5-8 那个"非管理员才炸"的缺陷
 * 是同一类。拆开后，"权限判定 + 命令渲染"与"执行"各归其位，也让绝大部分逻辑
 * 可以在没有 SSH 的前提下单测。</p>
 *
 * <p><b>与调度下发保持同款语义</b>：命令走同一套六层变量解析
 * （{@link VariableChainResolver}），同样按 {@code success_codes} 判定成败
 * （PRD §12.4-5），退出码 null 同样表示"进程未能确认启动"（D-23 边界）。
 * 差别只有一处：试运行没有任务/上游上下文，故第 1~5 层为空 —— 于是引用了上游输出的
 * 命令在这里会<b>明确报错，而不是带着 ${} 字面量去执行</b>。</p>
 *
 * <p><b>未做的两件事（已在 README-M3 §4 登记）</b>：① 不注入 {@code env_vars}
 * （一期 SSH 直连的调度侧同样不注入，试运行因此与真实下发一致）；
 * ② 不提供"终止"端点（E-02：SSH 直跑拿不到远端 PID，终止由 600 秒硬超时兜底）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorDryRunService {

    /** 试运行实例编号前缀（只在日志与 SSE 帧里做串联标识，不落库、不占业务编号序列）。 */
    public static final String DRY_RUN_ID_PREFIX = "DR-";

    /** 成功码缺省值（PRD §12.4-5：留空默认 {0}）。 */
    private static final List<Integer> DEFAULT_SUCCESS_CODES = List.of(0);

    private final OperatorVersionService versionService;
    private final OperatorService operatorService;
    private final OperatorParamDefMapper paramDefMapper;
    private final ExecutorNodeService nodeService;
    private final CredentialMapper credentialMapper;
    private final SecretCryptoService crypto;
    private final ExecutorClient executorClient;
    private final ObjectMapper objectMapper;

    /**
     * 一次试运行的完整"执行计划"——把请求线程上能判定的都判定完。
     *
     * <p>{@code displayCommand} 与 {@code resolvedCommand} 是同一条命令的两份渲染：
     * 前者把敏感参数值换成 {@code ***}（出网给前端看），后者是真正下发的原文。
     * 两份都由同一次解析产出，不存在"改了一处忘了另一处"的余地。</p>
     *
     * <p>{@code command} 里带着解密后的凭据材料，故本 record 重写了
     * {@link #toString()}，只输出编号与地址 —— 否则任何一次
     * {@code log.debug("{}", plan)} 都会把凭据写进日志文件。</p>
     */
    public record DryRunPlan(
            String dryRunId,
            String versionId,
            String versionNo,
            String operatorId,
            String operatorName,
            String nodeId,
            String nodeName,
            String machineIp,
            String displayCommand,
            String resolvedCommand,
            int timeoutSeconds,
            List<Integer> successCodes,
            Map<String, Object> snapshotParams,
            Map<String, String> sources,
            SecretMasker masker,
            ExecuteCommand command) {

        @Override
        public String toString() {
            return "DryRunPlan[" + dryRunId + " " + versionId + " node=" + nodeId
                    + "/" + machineIp + " timeout=" + timeoutSeconds + "s]";
        }
    }

    // ── plan：请求线程 ───────────────────────────────────────

    /**
     * 装配执行计划（校验 → 解析 → 渲染 → 组装指令）。
     *
     * <p>任何一步失败都在<b>动手之前</b>抛出，因此不会留下"已经连上了才发现参数没填"
     * 的半执行状态；SSE 通道此时还没建立，错误以普通 JSON 响应返回（见 Controller）。</p>
     */
    public DryRunPlan plan(String versionId, DryRunRequest request) {
        OperatorVersion version = versionService.requireVisible(versionId);
        Operator operator = operatorService.findById(version.getOperatorId());

        List<DryRunPlanValidator.ParamSpec> specs = loadSpecs(version.getId());
        List<FieldError> errors = DryRunPlanValidator.validate(request, specs);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }

        ExecutorNode node = requireRunnableNode(request.getExecutorNodeId());
        Credential credential = credentialOf(node);

        Map<String, Object> effectiveParams = mergeDefaults(specs, request.getParams());
        SecretMasker masker = SecretMasker.of(
                allMaskedValues(version, sensitiveParamValues(specs, effectiveParams)));

        // 六层覆盖链：试运行没有平台/项目/工作流/触发/上游五层，只有第 6 层（步骤参数）。
        // 让解析器（而不是本类）回答"值里还嵌着引用怎么办"：引用了上游输出的命令
        // 会在这里被拦下，而不是带着 "${step.X.output.y}" 字面量去执行。
        VariableChainResolver.Result resolved = VariableChainResolver.resolve(
                VariableChainResolver.Chain.empty(), effectiveParams, sensitiveKeys(specs));
        if (resolved.hasErrors()) {
            throw variableErrors(resolved.errors());
        }

        String startCommand = version.getStartCommand();
        if (startCommand == null || startCommand.isBlank()) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "该版本未配置启动命令，无法试运行",
                    Map.of("rule", "DRYRUN_COMMAND_MISSING"));
        }
        VariableChainResolver.Rendered real = VariableChainResolver.renderCommand(
                VariableChainResolver.Chain.empty(), resolved.resolvedParams(), startCommand);
        if (!real.errors().isEmpty()) {
            throw variableErrors(real.errors());
        }
        VariableChainResolver.Rendered display = VariableChainResolver.renderCommand(
                VariableChainResolver.Chain.empty(), resolved.snapshotParams(), startCommand);

        int timeout = effectiveTimeout(request, version);
        String dryRunId = DRY_RUN_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        List<Integer> successCodes = version.getSuccessCodes() == null || version.getSuccessCodes().length == 0
                ? DEFAULT_SUCCESS_CODES : List.of(version.getSuccessCodes());

        // 命令里可能<b>字面量</b>写着敏感值（不是通过 ${} 注入），快照渲染覆盖不到它，
        // 故再按值过一遍脱敏器 —— 两条路径都堵上，出网的那份才是真的干净
        String displayCommand = masker.mask(display.command());

        ExecuteCommand command = ExecuteCommand.builder()
                .stepInstanceId(dryRunId)     // 不占 SI- 序列：它只是本次实例的串联标识
                .attemptNo(1)
                .dispatchToken(dryRunId)
                .machineIp(node.getIp())
                .connectType("SSH")
                .username(credential.getUsername())
                .secretMaterial(crypto.decrypt(credential.getSecretEncrypted()))
                .resolvedCommand(real.command())
                .workDir(version.getWorkDir())
                .timeoutSeconds(timeout)
                .successCodes(successCodes)
                .build();

        Map<String, String> sources = new LinkedHashMap<>();
        resolved.sources().forEach((key, source) -> sources.put(key, source.layer().label()));

        log.info("试运行计划已装配 dry_run={} version={} node={}({}) timeout={}s 参数 {} 个（敏感 {} 个）",
                dryRunId, version.getVersionId(), node.getExecutorNodeId(), node.getIp(),
                timeout, resolved.resolvedParams().size(), masker.size());

        return new DryRunPlan(dryRunId, version.getVersionId(), version.getVersionNo(),
                operator != null ? operator.getOperatorId() : null,
                operator != null ? operator.getOperatorName() : null,
                node.getExecutorNodeId(), node.getExecutorNodeName(), node.getIp(),
                displayCommand, real.command(), timeout, successCodes,
                resolved.snapshotParams(), OrderedCollections.orderedMap(sources), masker, command);
    }

    // ── run：工作线程（无 ThreadLocal 上下文）─────────────────

    /**
     * 执行并逐行回调日志。
     *
     * <p>工作线程上不做任何查库/鉴权（原因见类注释），只调用执行客户端。
     * 回调前对每一行做敏感值脱敏 —— 脱敏在服务端执行、前端不接触原文
     * （PRD §13.3 硬要求，与 {@code LogPushRegistry} 同一口径）。</p>
     */
    public ExecuteResult run(DryRunPlan plan, ExecutorClient.LineListener listener) {
        ExecutorClient.LineListener masked = (stream, line) ->
                listener.onLine(stream, plan.masker().mask(line));
        ExecuteResult result = executorClient.execute(plan.command(), masked);
        log.info("试运行结束 dry_run={} exit_code={} 耗时 {}ms 判定 {}",
                plan.dryRunId(), result.getExitCode(),
                result.getDurationNanos() == null ? null : result.getDurationNanos() / 1_000_000,
                isSuccess(result.getExitCode(), plan.successCodes()) ? "成功" : "失败");
        return result;
    }

    /**
     * 成败判定（PRD §12.4-5）：退出码在 {@code success_codes} 内才算成功。
     *
     * <p>{@code exitCode == null} 恒为"失败"且语义特殊 —— 它是"进程未能确认启动"
     * （连接/认证失败、超时强杀），调用方据此提示"机器是否可达"而不是"命令写错了"。</p>
     */
    public static boolean isSuccess(Integer exitCode, List<Integer> successCodes) {
        return exitCode != null && successCodes != null && successCodes.contains(exitCode);
    }

    // ── 内部 ────────────────────────────────────────────────

    /** 目标节点必须可执行：可见（40301/40400）→ 已启用 → Linux → 已绑凭据。 */
    private ExecutorNode requireRunnableNode(String nodeId) {
        ExecutorNode node = nodeService.requireVisible(nodeId);
        if (!Boolean.TRUE.equals(node.getEnabled())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "执行节点已禁用，无法试运行",
                    Map.of("rule", "NODE_DISABLED", "node_id", nodeId));
        }
        if (!"LINUX".equals(node.getOsType())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "一期仅支持 Linux 节点试运行（Q-02）",
                    Map.of("rule", "NODE_OS_UNSUPPORTED", "node_id", nodeId));
        }
        if (node.getCredentialRefId() == null) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "节点未绑定凭据，无法试运行",
                    Map.of("rule", "NODE_CREDENTIAL_REQUIRED", "node_id", nodeId));
        }
        return node;
    }

    /**
     * 取节点绑定的凭据。
     *
     * <p>节点表只存 {@code credential_ref_id}（内部主键），凭据本体现查 ——
     * 不在节点行上缓存 secret 是刻意的：轮换凭据后节点不该还在用旧的那一份。</p>
     */
    private Credential credentialOf(ExecutorNode node) {
        Credential credential = credentialMapper.selectById(node.getCredentialRefId());
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "节点绑定的凭据不存在: " + node.getCredentialRefId(),
                    Map.of("resource_type", "CREDENTIAL",
                            "resource_id", String.valueOf(node.getCredentialRefId())));
        }
        return credential;
    }

    /** 参数规格：实体（jsonb 的 options 是字符串）→ 校验器要的纯数据视图。 */
    private List<DryRunPlanValidator.ParamSpec> loadSpecs(Long versionRowId) {
        List<DryRunPlanValidator.ParamSpec> specs = new ArrayList<>();
        for (OperatorParamDef def : paramDefMapper.listByVersionId(versionRowId)) {
            specs.add(new DryRunPlanValidator.ParamSpec(
                    def.getParamKey(), def.getName(), def.getParamType(),
                    Boolean.TRUE.equals(def.getRequired()),
                    def.getDefaultValue(),
                    !Boolean.FALSE.equals(def.getRuntimeOverridable()),
                    Boolean.TRUE.equals(def.getSensitive()),
                    readChoices(def.getOptions())));
        }
        return specs;
    }

    /**
     * 合并默认值（参数继承链第 4 层，PRD §12.5：步骤参数 &gt; 算子版本默认值）。
     *
     * <p>按模板的 {@code seq} 顺序落进 {@link LinkedHashMap}：命令回显与参数面板都按
     * 这个顺序展示，用户看到的顺序与上传时配的顺序一致。</p>
     *
     * <p>"没填"与"填了空串"一视同仁地取默认值 —— 参数面板上清空一个输入框的语义是
     * "我不指定"，把它当空字符串注入会得到 {@code --date } 这种残缺参数。</p>
     */
    private Map<String, Object> mergeDefaults(List<DryRunPlanValidator.ParamSpec> specs,
                                              Map<String, Object> provided) {
        Map<String, Object> effective = new LinkedHashMap<>();
        Map<String, Object> source = provided == null ? Map.of() : provided;
        for (DryRunPlanValidator.ParamSpec spec : specs) {
            Object value = source.get(spec.key());
            boolean hasValue = value != null && !(value instanceof String s && s.isBlank());
            if (hasValue) {
                effective.put(spec.key(), value);
            } else if (spec.defaultValue() != null && !spec.defaultValue().isBlank()) {
                effective.put(spec.key(), spec.defaultValue());
            }
        }
        return effective;
    }

    private Set<String> sensitiveKeys(List<DryRunPlanValidator.ParamSpec> specs) {
        Set<String> keys = new LinkedHashSet<>();
        for (DryRunPlanValidator.ParamSpec spec : specs) {
            if (spec.sensitive()) {
                keys.add(spec.key());
            }
        }
        return keys;
    }

    /** 敏感参数的<b>实际取值</b>（脱敏器按值替换，所以要从生效参数里取而不是从模板取）。 */
    private List<String> sensitiveParamValues(List<DryRunPlanValidator.ParamSpec> specs,
                                              Map<String, Object> effectiveParams) {
        List<String> values = new ArrayList<>();
        for (DryRunPlanValidator.ParamSpec spec : specs) {
            if (!spec.sensitive()) {
                continue;
            }
            Object value = effectiveParams.get(spec.key());
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    /**
     * 汇总所有必须脱敏的值：敏感参数取值 + 标记 secret 的环境变量取值。
     *
     * <p>环境变量本期不进试运行命令（见类注释），但仍然纳管：算子脚本可能自己把某个
     * secret 环境变量 echo 出来，日志侧的脱敏不该依赖"它有没有被注入"。</p>
     */
    private List<String> allMaskedValues(OperatorVersion version, List<String> paramValues) {
        List<String> values = new ArrayList<>(paramValues);
        for (OperatorVersionParts.EnvVar env : readEnvVars(version.getEnvVars())) {
            if (Boolean.TRUE.equals(env.getSecret()) && env.getValue() != null) {
                values.add(env.getValue());
            }
        }
        return values;
    }

    /**
     * 生效超时：请求值 &gt; 版本默认值 &gt; 平台上限 600。
     *
     * <p>版本默认值同样要<b>封顶</b>：它可能是很早以前按旧口径配的（例如 3600 秒），
     * 而"单次试运行上限 10 分钟"（PRD §10.6）是平台硬约束。两者处置不同是刻意的 ——
     * 请求值超限要报错（用户当场填错了，必须立刻告诉他），版本默认值超限则封顶执行
     * （历史配置不该让试运行直接不可用）。</p>
     */
    private int effectiveTimeout(DryRunRequest request, OperatorVersion version) {
        int value = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds()
                : version.getDefaultTimeoutSeconds() != null ? version.getDefaultTimeoutSeconds()
                : DryRunPlanValidator.MAX_TIMEOUT_SECONDS;
        return Math.min(Math.max(value, 1), DryRunPlanValidator.MAX_TIMEOUT_SECONDS);
    }

    private List<String> readChoices(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 存量数据里的坏 JSON 不该让试运行直接 500：退化成"不做候选值校验"，
            // 与 OperatorVersionService 读快照时的处置一致
            log.warn("参数候选值 JSON 解析失败，跳过候选值校验: {}", e.getMessage());
            return List.of();
        }
    }

    private List<OperatorVersionParts.EnvVar> readEnvVars(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, OperatorVersionParts.EnvVar.class));
        } catch (Exception e) {
            log.warn("环境变量 JSON 解析失败，按空集合处理: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 参数校验失败 → <b>42210 + errors[]</b>（与上传同码）。
     *
     * <p>为什么不新造一个码：docs/07 §4.2 的错误码表是单一真源，凭空添一个码会让前端
     * 为同一类"表单逐字段回填"错误写两套渲染（该纪律与"不在代码里凭空多出一个审计
     * 动作码"同源）。语义上 42210 是"算子表单校验失败"，试运行参数表单是它的同族。
     * 已登记 README-M3 §4。</p>
     */
    private BizException invalid(List<FieldError> errors) {
        String summary = errors.stream().map(FieldError::message).findFirst().orElse("试运行参数校验失败");
        return new BizException(ErrorCode.OPERATOR_UPLOAD_INVALID, summary, Map.of("errors", errors));
    }

    /** 启动命令里的引用落空/非法 → 42214（变量引用无效），绝不带着 ${} 去执行。 */
    private BizException variableErrors(List<String> messages) {
        List<FieldError> errors = messages.stream().map(m -> FieldError.of("start_command", m)).toList();
        return new BizException(ErrorCode.VARIABLE_REF_INVALID,
                messages.isEmpty() ? "启动命令变量解析失败" : messages.get(0), Map.of("errors", errors));
    }
}
