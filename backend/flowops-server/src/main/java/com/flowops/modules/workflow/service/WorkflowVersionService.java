package com.flowops.modules.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import com.flowops.domain.entity.workflow.WorkflowStep;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.workflow.WorkflowEdgeMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.mapper.workflow.WorkflowStepMapper;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.modules.workflow.converter.WorkflowVersionConverter;
import com.flowops.modules.workflow.dto.DagEdgeDef;
import com.flowops.modules.workflow.dto.DagStepDef;
import com.flowops.modules.workflow.dto.SaveWorkflowVersionRequest;
import com.flowops.modules.workflow.dto.WorkflowVersionVO;
import com.flowops.modules.workflow.validator.DagValidationContext;
import com.flowops.modules.workflow.validator.DagValidator;
import com.flowops.modules.workflow.validator.DagViolation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流版本服务（docs/05 §3.4 {@code workflow_version} / {@code workflow_step} /
 * {@code workflow_edge}；CONTRACT §6.2）。
 *
 * <p><b>不可变性（PRD §7.2-2/5/6）</b>：发布后版本<b>冻结</b> —— 不可改、不可删，
 * 因为任务按 {@code version_id} 绑定，历史任务必须能回答"当时执行的是哪张图"。
 * 修改已发布工作流的唯一途径是"基于当前版本新开草稿"（{@link #createDraft}）。</p>
 *
 * <p><b>草稿机制与 42215</b>：{@code has_draft_changes} 表达"存在未落地的修改"。
 * 新开草稿时若已有一个未发布的草稿，直接 42215 —— 否则会出现两个并行的草稿，
 * 而"发布哪个"在 UI 上没有可解释的答案。</p>
 *
 * <p><b>整包替换</b>：{@code PUT /workflow-versions/{id}} 收下整张图（CONTRACT 明文：
 * 不做步骤级增量接口）。落库顺序是死的：先删边 → 删步骤 → 插步骤（拿到新主键）→ 插边。
 * 反过来会在插边时撞 FK（引用到刚被删掉的步骤主键）。</p>
 *
 * <p><b>两个校验时机，两套规则</b>（docs/07 §9.2）：保存草稿只跑规则 1/5/10（结构），
 * 发布跑全量 10 条。理由见 {@link DagValidator}。</p>
 *
 * <p><b>写完之后统一"读回组参"</b>：{@link #saveDraft} / {@link #createDraft} 的返回值
 * 都经 {@link #readGraph} 从库里读回来，而不是把请求对象改改就返回。多一次读，换来的是
 * "响应 == 库里真实内容"这条可验证性质 —— 步骤编号、连线端点、解析后的外键都在读回时
 * 被真实地翻译了一遍，任何落库偏差都会当场暴露给调用方，而不是等到下次打开画布。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowVersionService {

    /** 纯函数校验器，无依赖 —— 直接持有实例，不走注入（也就不必为它做 mock）。 */
    private final DagValidator validator = new DagValidator();

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowStepMapper stepMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowAccessGuard guard;
    private final WorkflowVersionConverter converter;
    private final DagAssembler assembler;
    private final ObjectMapper objectMapper;

    // ── 查询 ────────────────────────────────────────────────

    /** 版本详情（含 DAG 全量）：画布的唯一读取入口。 */
    public WorkflowVersionVO get(String versionId) {
        WorkflowVersion version = requireVisible(versionId);
        return assemble(version, guard.requireVisibleById(version.getWorkflowId()));
    }

    // ── 新开草稿 ────────────────────────────────────────────

    /**
     * 基于当前版本新开草稿（CONTRACT §6.2 {@code POST /workflows/{workflowId}/versions}）。
     *
     * <p>把当前版本的节点与连线<b>整份复制</b>到新版本，而不是留一张空图：
     * PRD §10.7-4 的语义是"修改已发布工作流时基于当前版本创建新草稿"，
     * 空图会逼用户从头重画。</p>
     *
     * <p>从未发布过（一个版本都没有）的工作流也能新开草稿 —— 否则新建完就卡死，
     * 发布这一步永远到不了。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVersionVO createDraft(String workflowId) {
        Workflow workflow = guard.requireVisible(workflowId);
        if (Boolean.TRUE.equals(workflow.getHasDraftChanges())) {
            throw new BizException(ErrorCode.DRAFT_CHANGES_PENDING,
                    "工作流存在草稿变更，需先发布或丢弃",
                    Map.of("workflow_id", workflowId));
        }

        WorkflowVersion base = workflow.getCurrentVersionId() != null
                ? versionMapper.selectById(workflow.getCurrentVersionId())
                : latestVersion(workflow.getId());

        String wfDigits = digits(workflow.getWorkflowId());
        int versionIndex = nextVersionIndex(workflow.getId());

        WorkflowVersion draft = new WorkflowVersion();
        draft.setVersionId(versionId(wfDigits, versionIndex));
        draft.setWorkflowId(workflow.getId());
        draft.setVersionNo("v" + versionIndex);
        draft.setDagDefinition(base != null ? base.getDagDefinition() : "{\"steps\":[],\"edges\":[]}");
        draft.setWorkflowParams(base != null ? base.getWorkflowParams() : "[]");
        draft.setTriggerConfig(base != null ? base.getTriggerConfig() : "[]");
        draft.setStepCount(0);
        draft.setPublishStatus("DRAFT");
        draft.setCanvasWidth(base != null ? base.getCanvasWidth() : null);
        draft.setCanvasHeight(base != null ? base.getCanvasHeight() : null);
        draft.setVersion(0);
        draft.setDeleted(false);
        versionMapper.insert(draft);

        int copied = copyGraph(base, draft, wfDigits, versionIndex);
        // 复制后把 step_count 与 dag_definition 快照一起补齐（快照必须反映新版本的编号）
        draft.setStepCount(copied);
        draft.setDagDefinition(serializeGraph(readGraph(draft.getId())));
        versionMapper.updateById(draft);

        workflow.setHasDraftChanges(true);
        workflowMapper.updateById(workflow);
        log.info("工作流草稿已新开 workflow={} version={} 复制节点 {} 个",
                workflowId, draft.getVersionId(), copied);
        return assemble(draft, workflow);
    }

    // ── 保存草稿（整包）─────────────────────────────────────

    /**
     * 保存草稿（CONTRACT §6.2 {@code PUT /workflow-versions/{versionId}}）。
     *
     * <p>只跑结构校验（规则 1/5/10）。失败时返回 <b>42213 + errors[]</b>，每条带
     * {@code rule} 与 {@code step_name}，前端据此把错误挂到画布对应节点上。</p>
     *
     * <p>发布后版本冻结，{@code requireDraft} 会以 <b>42212</b> 拒绝编辑。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVersionVO saveDraft(String versionId, SaveWorkflowVersionRequest request) {
        WorkflowVersion version = requireVisible(versionId);
        Workflow workflow = guard.requireVisibleById(version.getWorkflowId());
        requireDraft(version);

        List<DagStepDef> steps = request.getSteps() == null ? List.of() : request.getSteps();
        List<DagEdgeDef> edges = request.getEdges() == null ? List.of() : request.getEdges();

        // ① 请求体本身能不能读成一张图（读不成 → 40001，见 DagAssembler 的说明）
        Map<String, Integer> indexByKey = assembler.indexByKey(steps, edges);
        // ② 业务编号 → 内部主键（同时得到"填了但查不到"的哨兵）
        DagAssembler.Resolution resolution = assembler.resolve(steps);
        // ③ 结构校验：规则 1 / 5 / 10
        DagValidationContext ctx = assembler.buildRequestContext(steps, assembler.toEdgeLinks(edges, indexByKey),
                workflow.getConcurrencyPolicy(), workflow.getMaxParallelRuns(), resolution, false);
        List<DagViolation> violations = validator.validate(ctx, DagValidator.Phase.STRUCTURAL);
        if (!violations.isEmpty()) {
            throw dagInvalid(violations);
        }

        int versionIndex = versionIndex(version.getVersionNo());
        String wfDigits = digits(workflow.getWorkflowId());

        // 整包替换：顺序是死的（见 WorkflowEdgeMapper 的类注释）
        edgeMapper.deleteByVersionId(version.getId());
        stepMapper.deleteByVersionId(version.getId());

        Map<String, Long> rowIdByClientKey = new HashMap<>();
        int seq = 1;
        for (DagStepDef step : steps) {
            WorkflowStep entity = toEntity(step, version.getId(), wfDigits, versionIndex, seq, resolution);
            stepMapper.insert(entity);
            rowIdByClientKey.put(step.getStepId(), entity.getId());
            seq++;
        }

        int edgeSeq = 1;
        for (DagEdgeDef edge : edges) {
            WorkflowEdge entity = new WorkflowEdge();
            entity.setEdgeId(edgeId(wfDigits, versionIndex, edgeSeq));
            entity.setWorkflowVersionId(version.getId());
            entity.setSourceStepId(rowIdByClientKey.get(edge.getSourceStepId()));
            entity.setTargetStepId(rowIdByClientKey.get(edge.getTargetStepId()));
            entity.setCreatedAt(OffsetDateTime.now());
            edgeMapper.insert(entity);
            edgeSeq++;
        }

        version.setWorkflowParams(writeJson(request.getWorkflowParams(), "[]"));
        if (request.getCanvasWidth() != null) {
            version.setCanvasWidth(request.getCanvasWidth());
        }
        if (request.getCanvasHeight() != null) {
            version.setCanvasHeight(request.getCanvasHeight());
        }
        // 快照与 step_count 一律从库里的真实结果生成，不信"我以为我插进去了几个"
        Graph graph = readGraph(version.getId());
        version.setStepCount(graph.steps().size());
        version.setDagDefinition(serializeGraph(graph));
        versionMapper.updateById(version);

        if (!Boolean.TRUE.equals(workflow.getHasDraftChanges())) {
            workflow.setHasDraftChanges(true);
            workflowMapper.updateById(workflow);
        }
        log.info("工作流草稿已保存 version={} 节点 {} 个 / 连线 {} 条",
                versionId, graph.steps().size(), graph.edges().size());
        return assemble(version, workflow);
    }

    // ── 发布 ────────────────────────────────────────────────

    /**
     * 发布版本（被 {@link WorkflowService#publish} 调用，同事务）。
     *
     * <p><b>校验对象是库里的数据，不是本次请求</b>：发布后版本冻结、任务会长期绑定它，
     * 此刻放过的错会变成线上事故。故这里从 {@code workflow_step}/{@code workflow_edge}
     * 读回整张图再跑全量 10 条。</p>
     *
     * @return 冻结后的版本实体（{@code current_version_id} 的切换由调用方完成）
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVersion publishVersion(Workflow workflow, String versionId) {
        WorkflowVersion version = findVersionOf(workflow, versionId);
        if ("ARCHIVED".equals(version.getPublishStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "已归档版本不可发布，当前状态: " + version.getPublishStatus(),
                    Map.of("current_status", version.getPublishStatus()));
        }
        if ("PUBLISHED".equals(version.getPublishStatus())) {
            // 把某个历史版本重新切回"当前版本"（回滚场景）。不改发布人与发布时间 ——
            // 那是"这一版第一次被发布"的事实，覆盖它会让审计回答不了"当时是谁发的"
            log.info("版本已是 PUBLISHED，仅切回当前版本 workflow={} version={}",
                    workflow.getWorkflowId(), versionId);
            return version;
        }

        DagValidationContext ctx = assembler.buildEntityContext(
                stepMapper.listByVersionId(version.getId()),
                edgeMapper.listByVersionId(version.getId()),
                workflow.getConcurrencyPolicy(), workflow.getMaxParallelRuns());
        List<DagViolation> violations = validator.validate(ctx, DagValidator.Phase.PUBLISH);
        if (!violations.isEmpty()) {
            throw dagInvalid(violations);
        }

        version.setPublishStatus("PUBLISHED");
        version.setPublisher(currentUsername());
        version.setPublishedAt(OffsetDateTime.now());
        versionMapper.updateById(version);
        log.info("工作流版本已发布 version={} workflow={}", versionId, workflow.getWorkflowId());
        return version;
    }

    // ── 可见性 ──────────────────────────────────────────────

    /**
     * 版本可见性：<b>借父工作流判定数据范围</b>。
     *
     * <p>{@code workflow_version} 没有 {@code project_id} 列（docs/05 §3.4），行级过滤
     * 是按 {@code workflow.project_id} 注入的、覆盖不到版本表 —— 若只按版本号查，
     * 任何登录用户都能读到别的项目的完整 DAG（节点参数、集群队列、变量引用），
     * 属于静默越权。故此处先取版本行，再回父工作流做可见性判定（40301/40400）。</p>
     */
    WorkflowVersion requireVisible(String versionId) {
        WorkflowVersion version = versionMapper.selectOne(Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getVersionId, versionId)
                .eq(WorkflowVersion::getDeleted, false));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流版本不存在: " + versionId,
                    Map.of("resource_type", "WORKFLOW_VERSION", "resource_id", versionId));
        }
        guard.requireVisibleById(version.getWorkflowId());
        return version;
    }

    /** 取某工作流下的版本；不存在或不属于该工作流一律 40400（不泄漏"这个版本号存在"）。 */
    private WorkflowVersion findVersionOf(Workflow workflow, String versionId) {
        WorkflowVersion version = versionMapper.selectOne(Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getVersionId, versionId)
                .eq(WorkflowVersion::getWorkflowId, workflow.getId())
                .eq(WorkflowVersion::getDeleted, false));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "工作流 " + workflow.getWorkflowId() + " 下不存在版本 " + versionId,
                    Map.of("resource_type", "WORKFLOW_VERSION", "resource_id", versionId));
        }
        return version;
    }

    private void requireDraft(WorkflowVersion version) {
        if (!"DRAFT".equals(version.getPublishStatus())) {
            throw new BizException(ErrorCode.VERSION_NOT_DRAFT,
                    "非草稿版本不可编辑，当前状态: " + version.getPublishStatus()
                            + "（PRD §7.2-5：已发布版本只可查看，修改请基于当前版本新开草稿）",
                    Map.of("current_status", version.getPublishStatus()));
        }
    }

    // ── 落库映射 ────────────────────────────────────────────

    private WorkflowStep toEntity(DagStepDef dto, Long versionRowId, String wfDigits, int versionIndex,
                                  int seq, DagAssembler.Resolution resolution) {
        WorkflowStep entity = new WorkflowStep();
        entity.setStepId(stepId(wfDigits, versionIndex, seq));
        entity.setWorkflowVersionId(versionRowId);
        entity.setStepName(dto.getStepName());
        entity.setStepType(dto.getStepType() == null ? "TASK" : dto.getStepType());
        entity.setDescription(dto.getDescription());
        // 解析不到的业务编号写 NULL 而不是哨兵：FK 列可空，写 -1 会直接撞外键约束
        entity.setOperatorId(resolution.idOf(resolution.operators(), dto.getOperatorId()));
        entity.setOperatorVersionId(resolution.idOf(resolution.operatorVersions(), dto.getOperatorVersionId()));
        entity.setParams(writeJson(dto.getParams(), "{}"));
        entity.setCustomParams(writeJson(dto.getCustomParams(), "{}"));
        entity.setTargetClusterId(resolution.idOf(resolution.clusters(), dto.getTargetClusterId()));
        entity.setTargetQueueId(resolution.idOf(resolution.queues(), dto.getTargetQueueId()));
        entity.setOsConstraint(dto.getOsConstraint());
        entity.setTagConstraint(dto.getTagConstraint() == null
                ? new String[0] : dto.getTagConstraint().toArray(String[]::new));
        entity.setCpu(dto.getCpu());
        entity.setGpu(dto.getGpu());
        entity.setMemory(dto.getMemory());
        entity.setDisk(dto.getDisk());
        entity.setTimeoutSeconds(dto.getTimeoutSeconds());
        entity.setRetryCount(dto.getRetryCount());
        entity.setRetryIntervalSeconds(dto.getRetryIntervalSeconds());
        entity.setFailureStrategy(dto.getFailureStrategy());
        entity.setMutexGroup(dto.getMutexGroup());
        entity.setPosX(dto.getPosX() == null ? BigDecimal.ZERO : dto.getPosX());
        entity.setPosY(dto.getPosY() == null ? BigDecimal.ZERO : dto.getPosY());
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    /**
     * 复制基础版本的图到新草稿，返回复制的节点数。
     *
     * <p>新版本号不同 ⇒ {@code step_id} 必然不同（{@code uk_wstep_step_id} 是全表唯一），
     * 所以不能照搬旧编号，必须按位置重新发号、并同步重建连线。</p>
     */
    private int copyGraph(WorkflowVersion base, WorkflowVersion draft, String wfDigits, int versionIndex) {
        if (base == null) {
            return 0;
        }
        List<WorkflowStep> baseSteps = stepMapper.listByVersionId(base.getId());
        if (baseSteps.isEmpty()) {
            return 0;
        }
        Map<Long, Integer> positionByBaseRowId = new HashMap<>();
        List<Long> newRowIds = new ArrayList<>();
        int seq = 1;
        for (WorkflowStep baseStep : baseSteps) {
            WorkflowStep copy = copyStep(baseStep);
            copy.setStepId(stepId(wfDigits, versionIndex, seq));
            copy.setWorkflowVersionId(draft.getId());
            stepMapper.insert(copy);
            positionByBaseRowId.put(baseStep.getId(), newRowIds.size());
            newRowIds.add(copy.getId());
            seq++;
        }

        int edgeSeq = 1;
        for (WorkflowEdge baseEdge : edgeMapper.listByVersionId(base.getId())) {
            Integer from = positionByBaseRowId.get(baseEdge.getSourceStepId());
            Integer to = positionByBaseRowId.get(baseEdge.getTargetStepId());
            if (from == null || to == null) {
                // 悬挂连线（历史脏数据）：跳过，而不是让"新开草稿"整个失败
                log.warn("基础版本 {} 存在悬挂连线，复制时跳过", base.getVersionId());
                continue;
            }
            WorkflowEdge copy = new WorkflowEdge();
            copy.setEdgeId(edgeId(wfDigits, versionIndex, edgeSeq));
            copy.setWorkflowVersionId(draft.getId());
            copy.setSourceStepId(newRowIds.get(from));
            copy.setTargetStepId(newRowIds.get(to));
            copy.setCreatedAt(OffsetDateTime.now());
            edgeMapper.insert(copy);
            edgeSeq++;
        }
        return newRowIds.size();
    }

    /** 步骤行的"内容字段"复制（主键、版本号、编号一律由调用方重新赋值）。 */
    private WorkflowStep copyStep(WorkflowStep source) {
        WorkflowStep copy = new WorkflowStep();
        copy.setStepName(source.getStepName());
        copy.setStepType(source.getStepType());
        copy.setDescription(source.getDescription());
        copy.setOperatorId(source.getOperatorId());
        copy.setOperatorVersionId(source.getOperatorVersionId());
        copy.setParams(source.getParams());
        copy.setCustomParams(source.getCustomParams());
        copy.setTargetClusterId(source.getTargetClusterId());
        copy.setTargetQueueId(source.getTargetQueueId());
        copy.setOsConstraint(source.getOsConstraint());
        copy.setTagConstraint(source.getTagConstraint());
        copy.setCpu(source.getCpu());
        copy.setGpu(source.getGpu());
        copy.setMemory(source.getMemory());
        copy.setDisk(source.getDisk());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setRetryCount(source.getRetryCount());
        copy.setRetryIntervalSeconds(source.getRetryIntervalSeconds());
        copy.setFailureStrategy(source.getFailureStrategy());
        copy.setMutexGroup(source.getMutexGroup());
        copy.setPosX(source.getPosX());
        copy.setPosY(source.getPosY());
        copy.setCreatedAt(OffsetDateTime.now());
        return copy;
    }

    // ── 读路径组装 ──────────────────────────────────────────

    /** 某版本落库后的图（DTO 形态，外键已翻回业务编号）。 */
    private record Graph(List<DagStepDef> steps, List<DagEdgeDef> edges) {
    }

    /**
     * 从库读回整张图并翻回业务编号（D-27）。
     *
     * <p>用 {@code workflow_step.id} 作为连线端点的映射键，而不是 {@code step_id}：
     * 前者天然唯一且与 {@code workflow_edge} 存的形态一致，省掉一次字符串匹配。</p>
     */
    private Graph readGraph(Long versionRowId) {
        List<WorkflowStep> stepRows = stepMapper.listByVersionId(versionRowId);
        Set<Long> operatorIds = new LinkedHashSet<>();
        Set<Long> versionIds = new LinkedHashSet<>();
        Set<Long> clusterIds = new LinkedHashSet<>();
        Set<Long> queueIds = new LinkedHashSet<>();
        for (WorkflowStep row : stepRows) {
            addIfPresent(operatorIds, row.getOperatorId());
            addIfPresent(versionIds, row.getOperatorVersionId());
            addIfPresent(clusterIds, row.getTargetClusterId());
            addIfPresent(queueIds, row.getTargetQueueId());
        }
        DagAssembler.ReverseRefs refs = assembler.toBusinessIds(operatorIds, versionIds, clusterIds, queueIds);

        Map<Long, String> stepIdByRowId = new LinkedHashMap<>();
        List<DagStepDef> steps = new ArrayList<>(stepRows.size());
        for (WorkflowStep row : stepRows) {
            stepIdByRowId.put(row.getId(), row.getStepId());
            steps.add(toDto(row, refs));
        }

        List<DagEdgeDef> edges = new ArrayList<>();
        for (WorkflowEdge row : edgeMapper.listByVersionId(versionRowId)) {
            String from = stepIdByRowId.get(row.getSourceStepId());
            String to = stepIdByRowId.get(row.getTargetStepId());
            if (from == null || to == null) {
                log.warn("版本行 {} 存在悬挂连线 {} -> {}", versionRowId,
                        row.getSourceStepId(), row.getTargetStepId());
                continue;
            }
            DagEdgeDef dto = new DagEdgeDef();
            dto.setSourceStepId(from);
            dto.setTargetStepId(to);
            edges.add(dto);
        }
        return new Graph(steps, edges);
    }

    private WorkflowVersionVO assemble(WorkflowVersion version, Workflow workflow) {
        WorkflowVersionVO vo = converter.toVO(version);
        vo.setVersionId(version.getVersionId());
        vo.setWorkflowId(workflow.getWorkflowId());
        vo.setWorkflowName(workflow.getWorkflowName());
        vo.setHasDraftChanges(workflow.getHasDraftChanges());
        vo.setWorkflowParams(readJsonList(version.getWorkflowParams()));
        Graph graph = readGraph(version.getId());
        vo.setSteps(graph.steps());
        vo.setEdges(graph.edges());
        return vo;
    }

    private DagStepDef toDto(WorkflowStep row, DagAssembler.ReverseRefs refs) {
        DagStepDef dto = new DagStepDef();
        dto.setStepId(row.getStepId());
        dto.setStepName(row.getStepName());
        dto.setStepType(row.getStepType());
        dto.setDescription(row.getDescription());
        dto.setOperatorId(refs.of(refs.operators(), row.getOperatorId()));
        dto.setOperatorVersionId(refs.of(refs.operatorVersions(), row.getOperatorVersionId()));
        dto.setParams(readObjectMap(row.getParams()));
        dto.setCustomParams(readObjectMap(row.getCustomParams()));
        dto.setTargetClusterId(refs.of(refs.clusters(), row.getTargetClusterId()));
        dto.setTargetQueueId(refs.of(refs.queues(), row.getTargetQueueId()));
        dto.setOsConstraint(row.getOsConstraint());
        dto.setTagConstraint(row.getTagConstraint() == null ? List.of() : List.of(row.getTagConstraint()));
        dto.setCpu(row.getCpu());
        dto.setGpu(row.getGpu());
        dto.setMemory(row.getMemory());
        dto.setDisk(row.getDisk());
        dto.setTimeoutSeconds(row.getTimeoutSeconds());
        dto.setRetryCount(row.getRetryCount());
        dto.setRetryIntervalSeconds(row.getRetryIntervalSeconds());
        dto.setFailureStrategy(row.getFailureStrategy());
        dto.setMutexGroup(row.getMutexGroup());
        dto.setPosX(row.getPosX());
        dto.setPosY(row.getPosY());
        return dto;
    }

    private void addIfPresent(Set<Long> target, Long id) {
        if (id != null) {
            target.add(id);
        }
    }

    // ── 编号 ────────────────────────────────────────────────

    /** {@code WFV-<工作流数字段>-<两位版本序号>}，与算子版本 {@code OPV-xxxx-xx} 同构。 */
    private String versionId(String wfDigits, int versionIndex) {
        return "WFV-" + wfDigits + "-" + String.format("%02d", versionIndex);
    }

    /** {@code WFS-<工作流数字段>-<两位版本序号>-<两位步骤序号>}（≤ 32 字符，全表唯一）。 */
    private String stepId(String wfDigits, int versionIndex, int seq) {
        return "WFS-" + wfDigits + "-" + pad(versionIndex) + "-" + pad(seq);
    }

    private String edgeId(String wfDigits, int versionIndex, int seq) {
        return "WFE-" + wfDigits + "-" + pad(versionIndex) + "-" + pad(seq);
    }

    private String pad(int value) {
        return String.format("%02d", value);
    }

    private WorkflowVersion latestVersion(Long workflowRowId) {
        return versionMapper.selectOne(Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getWorkflowId, workflowRowId)
                .eq(WorkflowVersion::getDeleted, false)
                .orderByDesc(WorkflowVersion::getId)
                .last("LIMIT 1"));
    }

    /**
     * 下一个版本序号。
     *
     * <p>{@code selectMaxVersionIndex} <b>含软删行</b>（见 WorkflowVersionMapper 的注释）：
     * 跳过已删行会重复生成同一个 {@code version_id}，撞全表唯一索引。</p>
     */
    private int nextVersionIndex(Long workflowRowId) {
        Integer max = versionMapper.selectMaxVersionIndex(workflowRowId);
        return (max == null ? 0 : max) + 1;
    }

    private int versionIndex(String versionNo) {
        String digits = digits(versionNo);
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private String digits(String value) {
        return value == null ? "0" : value.replaceAll("\\D", "");
    }

    // ── 工具 ────────────────────────────────────────────────

    private BizException dagInvalid(List<DagViolation> violations) {
        DagViolation first = violations.get(0);
        // 错误码取"第一条违规"的：规则顺序 = 输出顺序（DagValidator 固定按 1→10 收集），
        // 所以这个选择是确定的而非碰巧的。全部违规仍放在 errors[] 里。
        ErrorCode code = switch (first.errorCode()) {
            case DagViolation.CODE_VARIABLE -> ErrorCode.VARIABLE_REF_INVALID;
            case DagViolation.CODE_UNPUBLISHED_OPERATOR -> ErrorCode.OPERATOR_VERSION_NOT_PUBLISHED;
            default -> ErrorCode.DAG_VALIDATE_FAILED;
        };
        return new BizException(code, first.message(), Map.of("errors", violations));
    }

    private String serializeGraph(Graph graph) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("steps", graph.steps());
        payload.put("edges", graph.edges());
        return writeJson(payload, "{\"steps\":[],\"edges\":[]}");
    }

    private String writeJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "DAG 序列化失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("工作流参数快照解析失败，按空集合返回: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("步骤参数快照解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String currentUsername() {
        var ctx = UserContext.get();
        return ctx != null ? ctx.getUsername() : "system";
    }
}
