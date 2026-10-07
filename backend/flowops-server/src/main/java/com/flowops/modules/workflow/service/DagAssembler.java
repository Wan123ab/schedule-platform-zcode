package com.flowops.modules.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorOutputDecl;
import com.flowops.domain.entity.asset.OperatorParamDef;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import com.flowops.domain.entity.workflow.WorkflowStep;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorOutputDeclMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.modules.workflow.dto.DagEdgeDef;
import com.flowops.modules.workflow.dto.DagStepDef;
import com.flowops.modules.workflow.validator.DagValidationContext;
import com.flowops.modules.workflow.validator.DagValidationContext.Concurrency;
import com.flowops.modules.workflow.validator.DagValidationContext.EdgeLink;
import com.flowops.modules.workflow.validator.DagValidationContext.OperatorSpec;
import com.flowops.modules.workflow.validator.DagValidationContext.ResourceLimit;
import com.flowops.modules.workflow.validator.DagValidationContext.StepNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DAG 请求体 ⇄ {@link DagValidationContext} 的装配器（docs/07 §9.2 的"取数"侧）。
 *
 * <p><b>为什么必须是独立的一层</b>：{@code DagValidator} 是纯函数（这样"逐条规则造用例"
 * 才是真单测），代价就是有人得把跨域数据喂进去 —— 这个"有人"就是本类。把取数集中在这里、
 * 而不是散在保存流程里，换来两件事：① 校验器永远拿不到半成品上下文；
 * ② 业务编号 ↔ 内部主键的翻译只有一份实现，落库与校验不会各解析一套。</p>
 *
 * <p><b>本类在 {@code service} 包内是刻意的</b>：{@code ArchitectureTest} 规定 Mapper 只允许被
 * {@code com.flowops.modules..service..} 访问；放到隔壁的 {@code ..assembler..} 包会当场违反分层。</p>
 *
 * <p><b>两条装配路径，刻意不合并</b>：</p>
 * <ul>
 *   <li>{@link #buildRequestContext}：入参是<b>请求 DTO</b>（业务编号形态），保存草稿走这条；</li>
 *   <li>{@link #buildEntityContext}：入参是<b>库里的实体</b>（已经就是内部主键），发布走这条。</li>
 * </ul>
 * <p>发布校验的对象必须是"即将被冻结的那份库内数据"，而不是"本次请求"—— 两者在
 * "上一次保存被绕过/被后台改过"时会不同。走实体路径还顺带省掉四类反向翻译查询。</p>
 *
 * <p><b>业务编号解析不出来的两种后果，是两种错误码</b>：</p>
 * <ul>
 *   <li>落库时：写 {@code NULL}（FK 列可空），不能让 {@code -1} 撞外键；</li>
 *   <li>校验时：写哨兵 {@link DagValidationContext#UNRESOLVED_ID}，让规则 7 报
 *       <b>42218「引用了未发布的算子版本」</b>，而不是被规则 2 误报成"没选算子版本"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagAssembler {

    private final OperatorMapper operatorMapper;
    private final OperatorVersionMapper operatorVersionMapper;
    private final OperatorParamDefMapper paramDefMapper;
    private final OperatorOutputDeclMapper outputDeclMapper;
    private final ClusterMapper clusterMapper;
    private final QueueMapper queueMapper;
    private final ObjectMapper objectMapper;

    /**
     * 业务编号 → 内部主键的解析结果。
     *
     * <p>四个 Map 都只包含"解析成功"的项；查不到的键<b>不进来</b>（而不是映射成 null），
     * 这样 {@link #idOrSentinel} 才能区分"没填"与"填了但不存在"。</p>
     */
    public record Resolution(Map<String, Long> operators,
                             Map<String, Long> operatorVersions,
                             Map<String, Long> clusters,
                             Map<String, Long> queues) {

        /** 出网编号 → 内部主键；{@code ref} 为空或解析不到都返回 null（<b>落库</b>用）。 */
        public Long idOf(Map<String, Long> table, String ref) {
            return ref == null || ref.isBlank() ? null : table.get(ref);
        }

        /** 出网编号 → 内部主键；{@code ref} 为空返回 null，解析不到返回哨兵（<b>校验</b>用）。 */
        public Long idOrSentinel(Map<String, Long> table, String ref) {
            if (ref == null || ref.isBlank()) {
                return null;
            }
            Long id = table.get(ref);
            return id != null ? id : DagValidationContext.UNRESOLVED_ID;
        }
    }

    /** 内部主键 → 业务编号的反向翻译结果（查不到的键不进 Map，取值得到 null）。 */
    public record ReverseRefs(Map<Long, String> operators,
                              Map<Long, String> operatorVersions,
                              Map<Long, String> clusters,
                              Map<Long, String> queues) {

        public String of(Map<Long, String> table, Long id) {
            return id == null ? null : table.get(id);
        }
    }

    // ── 1. 请求体自身的完整性（40001 而不是 42213）────────────

    /**
     * 校验"这份请求体能不能被读成一张图"，并返回 {@code 步骤键 → 下标}。
     *
     * <p>这里的三类问题都<b>不是</b> PRD §10.8 的 8 条规则，而是请求体本身不合法
     * （真实画布产不出来）：步骤键重复、连线端点不存在、自环。它们走
     * {@code 40001 参数校验失败}；业务语义上的"成环"才是规则 5 → 42213。
     * 若把这几条也塞进 {@code errors[].rule}，就得凭空发明规则号 —— 而 docs/07 §9.2
     * 的规则编号是前后端共用的合约，不能自造。</p>
     */
    public Map<String, Integer> indexByKey(List<DagStepDef> steps, List<DagEdgeDef> edges) {
        Map<String, Integer> index = new HashMap<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            String key = steps.get(i).getStepId();
            if (index.putIfAbsent(key, i) != null) {
                duplicated.add(key);
            }
        }
        if (!duplicated.isEmpty()) {
            throw paramInvalid("步骤内部键重复：" + String.join("、", duplicated),
                    Map.of("duplicated_step_keys", List.copyOf(duplicated)));
        }
        for (DagEdgeDef edge : edges) {
            if (!index.containsKey(edge.getSourceStepId()) || !index.containsKey(edge.getTargetStepId())) {
                throw paramInvalid("连线引用了不存在的步骤："
                                + edge.getSourceStepId() + " → " + edge.getTargetStepId(),
                        Map.of("source_step_id", edge.getSourceStepId(),
                                "target_step_id", edge.getTargetStepId()));
            }
            if (edge.getSourceStepId().equals(edge.getTargetStepId())) {
                // DDL 的 ck_edge_no_self_loop 也会拦，但那时报的是 50001；提前报成人话
                throw paramInvalid("连线不能指向自身：" + edge.getSourceStepId(),
                        Map.of("step_id", edge.getSourceStepId()));
            }
        }
        return index;
    }

    /** 连线 → 下标对（{@code DagValidator} 只认下标，不认业务编号）。 */
    public List<EdgeLink> toEdgeLinks(List<DagEdgeDef> edges, Map<String, Integer> index) {
        List<EdgeLink> links = new ArrayList<>(edges.size());
        for (DagEdgeDef edge : edges) {
            links.add(new EdgeLink(index.get(edge.getSourceStepId()), index.get(edge.getTargetStepId())));
        }
        return links;
    }

    // ── 2. 业务编号 → 内部主键（保存草稿用）──────────────────

    /** 批量解析四类外键，一次 IN 查询一类，查询次数与节点数解耦。 */
    public Resolution resolve(List<DagStepDef> steps) {
        Map<String, Long> operators = resolveIds(
                distinct(steps, DagStepDef::getOperatorId),
                ids -> operatorMapper.selectList(Wrappers.<Operator>lambdaQuery()
                                .in(Operator::getOperatorId, ids)).stream()
                        .collect(Collectors.toMap(Operator::getOperatorId, Operator::getId)));
        Map<String, Long> versions = resolveIds(
                distinct(steps, DagStepDef::getOperatorVersionId),
                ids -> operatorVersionMapper.selectList(Wrappers.<OperatorVersion>lambdaQuery()
                                .in(OperatorVersion::getVersionId, ids)).stream()
                        .collect(Collectors.toMap(OperatorVersion::getVersionId, OperatorVersion::getId)));
        Map<String, Long> clusters = resolveIds(
                distinct(steps, DagStepDef::getTargetClusterId),
                ids -> clusterMapper.selectList(Wrappers.<Cluster>lambdaQuery()
                                .in(Cluster::getClusterId, ids)).stream()
                        .collect(Collectors.toMap(Cluster::getClusterId, Cluster::getId)));
        Map<String, Long> queues = resolveIds(
                distinct(steps, DagStepDef::getTargetQueueId),
                ids -> queueMapper.selectList(Wrappers.<Queue>lambdaQuery()
                                .in(Queue::getQueueId, ids)).stream()
                        .collect(Collectors.toMap(Queue::getQueueId, Queue::getId)));
        return new Resolution(operators, versions, clusters, queues);
    }

    private Set<String> distinct(List<DagStepDef> steps, Function<DagStepDef, String> getter) {
        Set<String> refs = new HashSet<>();
        for (DagStepDef step : steps) {
            String ref = getter.apply(step);
            if (ref != null && !ref.isBlank()) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /** 空集合直接短路：{@code IN ()} 是非法 SQL，而"所有步骤都没选算子"是常态而非异常。 */
    private Map<String, Long> resolveIds(Set<String> refs, Function<Collection<String>, Map<String, Long>> query) {
        return refs.isEmpty() ? Map.of() : query.apply(refs);
    }

    // ── 3. 内部主键 → 业务编号（回读画布用，D-27 的反向）───────

    /**
     * 批量把步骤里的四类内部主键翻回业务编号（画布渲染 {@code GET /workflow-versions/{id}}）。
     *
     * <p>查不到的行不进 Map、取值得到 {@code null} —— 把 {@code 12} 当业务编号返回出去，
     * 前端会把它当成一个真的编号去引用。</p>
     */
    public ReverseRefs toBusinessIds(Collection<Long> operatorIds, Collection<Long> operatorVersionIds,
                                     Collection<Long> clusterIds, Collection<Long> queueIds) {
        Map<Long, String> operators = reverse(operatorIds,
                ids -> operatorMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Operator::getId, Operator::getOperatorId)));
        Map<Long, String> versions = reverse(operatorVersionIds,
                ids -> operatorVersionMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(OperatorVersion::getId, OperatorVersion::getVersionId)));
        Map<Long, String> clusters = reverse(clusterIds,
                ids -> clusterMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Cluster::getId, Cluster::getClusterId)));
        Map<Long, String> queues = reverse(queueIds,
                ids -> queueMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Queue::getId, Queue::getQueueId)));
        return new ReverseRefs(operators, versions, clusters, queues);
    }

    private Map<Long, String> reverse(Collection<Long> ids, Function<Collection<Long>, Map<Long, String>> query) {
        return ids == null || ids.isEmpty() ? Map.of() : query.apply(ids);
    }

    // ── 4. 装配校验上下文 ───────────────────────────────────

    /**
     * 从<b>请求 DTO</b> 装配（保存草稿路径）。
     *
     * @param full {@code false} 时不查跨域数据（算子规格 / 集群上限）—— 结构校验只跑规则
     *             1/5/10，只需要图本身。这一步省下的是编辑器里最高频写操作上的三次额外查询
     */
    public DagValidationContext buildRequestContext(List<DagStepDef> steps,
                                                    List<EdgeLink> edgeLinks,
                                                    String concurrencyPolicy,
                                                    Integer maxParallelRuns,
                                                    Resolution resolution,
                                                    boolean full) {
        List<StepNode> nodes = new ArrayList<>(steps.size());
        Set<Long> versionRowIds = new LinkedHashSet<>();
        Set<Long> clusterRowIds = new LinkedHashSet<>();
        for (DagStepDef step : steps) {
            Long versionId = resolution.idOrSentinel(resolution.operatorVersions(), step.getOperatorVersionId());
            Long clusterId = resolution.idOrSentinel(resolution.clusters(), step.getTargetClusterId());
            collect(versionRowIds, versionId);
            collect(clusterRowIds, clusterId);
            nodes.add(new StepNode(
                    step.getStepName(),
                    step.getStepType(),
                    resolution.idOrSentinel(resolution.operators(), step.getOperatorId()),
                    versionId,
                    step.getParams(),
                    step.getCustomParams(),
                    clusterId,
                    step.getCpu(), step.getGpu(), step.getMemory(), step.getDisk(),
                    step.getRetryCount()));
        }
        return new DagValidationContext(nodes, edgeLinks, new Concurrency(concurrencyPolicy, maxParallelRuns),
                full ? loadOperatorSpecs(versionRowIds) : Map.of(),
                full ? loadClusterSpecs(clusterRowIds) : Map.of());
    }

    /**
     * 从<b>库内实体</b>装配（发布路径）。
     *
     * <p>实体的外键列本来就是内部主键，无需任何反向翻译；节点顺序 = 表的物理顺序
     * （{@code listByVersionId} 按 {@code id} 排序，与插入顺序一致，即画布保存时的顺序）。</p>
     */
    public DagValidationContext buildEntityContext(List<WorkflowStep> steps,
                                                   List<WorkflowEdge> edges,
                                                   String concurrencyPolicy,
                                                   Integer maxParallelRuns) {
        List<StepNode> nodes = new ArrayList<>(steps.size());
        Map<Long, Integer> indexByRowId = new HashMap<>();
        Set<Long> versionRowIds = new LinkedHashSet<>();
        Set<Long> clusterRowIds = new LinkedHashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            indexByRowId.put(step.getId(), i);
            collect(versionRowIds, step.getOperatorVersionId());
            collect(clusterRowIds, step.getTargetClusterId());
            // 库里存的是内部主键；为 null 表示"当时就没选/解析不到" → 规则 2 会给出正确提示
            nodes.add(new StepNode(
                    step.getStepName(),
                    step.getStepType(),
                    step.getOperatorId(),
                    step.getOperatorVersionId(),
                    readObjectMap(step.getParams()),
                    readObjectMap(step.getCustomParams()),
                    step.getTargetClusterId(),
                    step.getCpu(), step.getGpu(), step.getMemory(), step.getDisk(),
                    step.getRetryCount()));
        }
        List<EdgeLink> edgeLinks = new ArrayList<>(edges.size());
        for (WorkflowEdge edge : edges) {
            Integer from = indexByRowId.get(edge.getSourceStepId());
            Integer to = indexByRowId.get(edge.getTargetStepId());
            if (from != null && to != null) {
                edgeLinks.add(new EdgeLink(from, to));
            }
        }
        return new DagValidationContext(nodes, edgeLinks, new Concurrency(concurrencyPolicy, maxParallelRuns),
                loadOperatorSpecs(versionRowIds), loadClusterSpecs(clusterRowIds));
    }

    /** 哨兵与非正数都不该进"要查库"的集合（哨兵查出来一定是空，白跑一次 IN）。 */
    private void collect(Set<Long> target, Long id) {
        if (id != null && id > 0) {
            target.add(id);
        }
    }

    /**
     * 算子版本规格：发布状态（规则 7）+ 必填参数（规则 3）+ 已声明输出（规则 4）。
     *
     * <p>三个字段各有各的坑：① 只有 {@code PUBLISHED} 能被发布的工作流引用；
     * ② 必填参数按 {@code param_key} 而不是展示名比对；③ 声明输出为空集时规则 4
     * <b>跳过</b>"变量是否存在"的判定 —— 拿一份没有声明的规格去断言"变量不存在"，
     * 会把所有引用该算子输出的合法工作流全部拦下。</p>
     */
    private Map<Long, OperatorSpec> loadOperatorSpecs(Set<Long> versionRowIds) {
        if (versionRowIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> statusById = operatorVersionMapper.selectBatchIds(versionRowIds).stream()
                .collect(Collectors.toMap(OperatorVersion::getId, OperatorVersion::getPublishStatus));

        Map<Long, Set<String>> requiredById = new HashMap<>();
        for (OperatorParamDef def : paramDefMapper.listByVersionIds(versionRowIds)) {
            if (Boolean.TRUE.equals(def.getRequired())) {
                requiredById.computeIfAbsent(def.getOperatorVersionId(), k -> new LinkedHashSet<>())
                        .add(def.getParamKey());
            }
        }
        Map<Long, Set<String>> outputsById = new HashMap<>();
        for (OperatorOutputDecl decl : outputDeclMapper.listByVersionIds(versionRowIds)) {
            outputsById.computeIfAbsent(decl.getOperatorVersionId(), k -> new LinkedHashSet<>())
                    .add(decl.getVarName());
        }

        Map<Long, OperatorSpec> specs = new HashMap<>();
        statusById.forEach((id, status) -> specs.put(id, new OperatorSpec(status,
                requiredById.getOrDefault(id, Set.of()),
                outputsById.getOrDefault(id, Set.of()))));
        return specs;
    }

    /**
     * 集群资源上限（规则 6）。
     *
     * <p>只按<b>集群</b>聚合：{@code queue} 表只有 {@code max_concurrent_tasks} /
     * {@code max_waiting_tasks}（并发口径），没有资源上限列 —— 这是 docs/05 的现状，
     * 已登记为 README-M3 的偏离项。</p>
     */
    private Map<Long, DagValidationContext.ClusterSpec> loadClusterSpecs(Set<Long> clusterRowIds) {
        if (clusterRowIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DagValidationContext.ClusterSpec> specs = new HashMap<>();
        for (Cluster cluster : clusterMapper.selectBatchIds(clusterRowIds)) {
            specs.put(cluster.getId(), new DagValidationContext.ClusterSpec(
                    cluster.getClusterId(), cluster.getClusterName(),
                    new ResourceLimit(cluster.getCpuTotal(), cluster.getGpuTotal(),
                            cluster.getMemoryTotal(), cluster.getDiskTotal())));
        }
        return specs;
    }

    private BizException paramInvalid(String message, Map<String, Object> payload) {
        return new BizException(ErrorCode.PARAM_INVALID, message, payload);
    }

    /**
     * {@code params} / {@code custom_params} 是 JSONB，实体侧用 String 承载 —— 装配时必须解析回
     * Map，否则规则 3（必填参数）会把每个参数都判成缺失，规则 4（变量引用）则<b>一条都检查不到</b>。
     *
     * <p>后者是静默失效：不报错、不提示，只是校验少了一半。故解析失败时按空对象继续
     * （规则 3 会响亮地报"缺少必填参数"，而不是悄悄放过）。</p>
     */
    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("步骤参数快照解析失败，按空对象参与校验: {}", e.getMessage());
            return Map.of();
        }
    }
}
