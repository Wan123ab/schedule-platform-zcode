package com.flowops.modules.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import com.flowops.domain.entity.workflow.WorkflowStep;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorOutputDeclMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.workflow.WorkflowEdgeMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.mapper.workflow.WorkflowStepMapper;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.modules.governance.scope.ScopeGuard;
import com.flowops.modules.workflow.converter.WorkflowVersionConverterImpl;
import com.flowops.modules.workflow.dto.DagEdgeDef;
import com.flowops.modules.workflow.dto.DagStepDef;
import com.flowops.modules.workflow.dto.SaveWorkflowVersionRequest;
import com.flowops.modules.workflow.dto.WorkflowVersionVO;
import com.flowops.modules.workflow.validator.DagViolation;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流版本服务单测（CONTRACT §6.2；docs/07 §9.2）。
 *
 * <p><b>为什么用"内存伪库"而不是让 {@code listByVersionId} 返回默认空列表</b>：
 * 本类最想证明的一条性质是「<b>响应 == 库里真实内容</b>」—— 保存/新开草稿的返回值都经
 * {@code readGraph} 从库读回来。若让读接口返回空列表，这条链路就退化成"插入没报错就算过"，
 * 步骤编号、连线端点、外键翻译全都测不到。故这里给 {@code workflow_step} /
 * {@code workflow_edge} / {@code workflow_version} 各配一个 3 行的内存实现，
 * 让 {@code insert} → {@code listByVersionId} 真的能读回写进去的东西。</p>
 *
 * <p>{@link DagAssembler} 用<b>真实实现</b>（只 mock 它依赖的资产域 Mapper）：
 * 业务编号 → 内部主键的翻译只有一份实现，测试若把它也 mock 掉，
 * "解析不到写 null 而不是哨兵"这条关键区分就没人验了。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowVersionServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private WorkflowStepMapper stepMapper;
    @Mock private WorkflowEdgeMapper edgeMapper;
    @Mock private OperatorMapper operatorMapper;
    @Mock private OperatorVersionMapper operatorVersionMapper;
    @Mock private OperatorParamDefMapper paramDefMapper;
    @Mock private OperatorOutputDeclMapper outputDeclMapper;
    @Mock private ClusterMapper clusterMapper;
    @Mock private QueueMapper queueMapper;

    /** 内存"库"：三个集合就是被 mock 掉的那几张表。 */
    private final Map<Long, WorkflowVersion> versionRows = new LinkedHashMap<>();
    private final List<WorkflowStep> stepRows = new ArrayList<>();
    private final List<WorkflowEdge> edgeRows = new ArrayList<>();
    private long seq = 100;

    private WorkflowVersionService service;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper();
        DagAssembler assembler = new DagAssembler(operatorMapper, operatorVersionMapper, paramDefMapper,
                outputDeclMapper, clusterMapper, queueMapper, json);
        WorkflowAccessGuard guard = new WorkflowAccessGuard(workflowMapper, new ScopeGuard());
        service = new WorkflowVersionService(workflowMapper, versionMapper, stepMapper, edgeMapper,
                guard, new WorkflowVersionConverterImpl(), assembler, json);
        fakePersistence();
    }

    // ── 内存伪库 ────────────────────────────────────────────

    private void fakePersistence() {
        doAnswer(inv -> {
            WorkflowVersion v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(++seq);
            }
            versionRows.put(v.getId(), v);
            return 1;
        }).when(versionMapper).insert(any(WorkflowVersion.class));
        doAnswer(inv -> {
            WorkflowVersion v = inv.getArgument(0);
            versionRows.put(v.getId(), v);
            return 1;
        }).when(versionMapper).updateById(any(WorkflowVersion.class));
        when(versionMapper.selectById(any())).thenAnswer(inv -> versionRows.get((Long) inv.getArgument(0)));

        doAnswer(inv -> {
            WorkflowStep s = inv.getArgument(0);
            s.setId(++seq);
            stepRows.add(s);
            return 1;
        }).when(stepMapper).insert(any(WorkflowStep.class));
        when(stepMapper.listByVersionId(any())).thenAnswer(inv -> {
            Long versionRowId = inv.getArgument(0);
            return stepRows.stream().filter(s -> versionRowId.equals(s.getWorkflowVersionId())).toList();
        });
        when(stepMapper.deleteByVersionId(any())).thenAnswer(inv -> {
            Long versionRowId = inv.getArgument(0);
            return stepRows.removeIf(s -> versionRowId.equals(s.getWorkflowVersionId())) ? 1 : 0;
        });

        doAnswer(inv -> {
            WorkflowEdge e = inv.getArgument(0);
            e.setId(++seq);
            edgeRows.add(e);
            return 1;
        }).when(edgeMapper).insert(any(WorkflowEdge.class));
        when(edgeMapper.listByVersionId(any())).thenAnswer(inv -> {
            Long versionRowId = inv.getArgument(0);
            return edgeRows.stream().filter(e -> versionRowId.equals(e.getWorkflowVersionId())).toList();
        });
        when(edgeMapper.deleteByVersionId(any())).thenAnswer(inv -> {
            Long versionRowId = inv.getArgument(0);
            return edgeRows.removeIf(e -> versionRowId.equals(e.getWorkflowVersionId())) ? 1 : 0;
        });
    }

    // ── 夹具 ────────────────────────────────────────────────

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(11L);
        workflow.setWorkflowId("WF-0001");
        workflow.setWorkflowName("日增量清算");
        workflow.setStatus("DRAFT");
        workflow.setHasDraftChanges(false);
        workflow.setConcurrencyPolicy("FORBID");
        workflow.setMaxParallelRuns(1);
        workflow.setDeleted(false);
        return workflow;
    }

    private WorkflowVersion version(Long id, String versionId, String no, String status) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setVersionId(versionId);
        version.setWorkflowId(11L);
        version.setVersionNo(no);
        version.setPublishStatus(status);
        version.setStepCount(0);
        version.setVersion(0);
        version.setDeleted(false);
        return version;
    }

    /** 库里已有一行步骤（模拟上一次保存的结果）。 */
    private WorkflowStep storedStep(Long id, Long versionRowId, String stepId, String name) {
        WorkflowStep step = new WorkflowStep();
        step.setId(id);
        step.setWorkflowVersionId(versionRowId);
        step.setStepId(stepId);
        step.setStepName(name);
        step.setStepType("TASK");
        return step;
    }

    private WorkflowEdge storedEdge(Long id, Long versionRowId, Long from, Long to) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setWorkflowVersionId(versionRowId);
        edge.setEdgeId("WFE-0001-01-01");
        edge.setSourceStepId(from);
        edge.setTargetStepId(to);
        return edge;
    }

    /** 造一个"库里存在且可见"的草稿版本，并把守卫两跳都桩好。 */
    private WorkflowVersion givenDraftVersion(String versionId, String versionNo) {
        WorkflowVersion version = version(31L, versionId, versionNo, "DRAFT");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        when(workflowMapper.selectById(11L)).thenReturn(workflow());
        return version;
    }

    private DagStepDef step(String key, String name) {
        DagStepDef def = new DagStepDef();
        def.setStepId(key);
        def.setStepName(name);
        def.setStepType("TASK");
        return def;
    }

    private DagEdgeDef edge(String from, String to) {
        DagEdgeDef def = new DagEdgeDef();
        def.setSourceStepId(from);
        def.setTargetStepId(to);
        return def;
    }

    private SaveWorkflowVersionRequest request(List<DagStepDef> steps, List<DagEdgeDef> edges) {
        SaveWorkflowVersionRequest request = new SaveWorkflowVersionRequest();
        request.setSteps(steps);
        request.setEdges(edges);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<DagViolation> errorsOf(BizException e) {
        return (List<DagViolation>) ((Map<String, Object>) e.getPayload()).get("errors");
    }

    private static int codeOf(Throwable e) {
        return ((BizException) e).getErrorCode().getCode();
    }

    // ── 保存草稿：只跑结构校验 ───────────────────────────────

    @Test
    void 保存草稿_只跑结构校验_未绑算子的步骤也能存下() {
        givenDraftVersion("WFV-0001-01", "v1");

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01", request(List.of(step("s1", "抽取")), List.of()));

        // 规则 2 在保存阶段不跑：否则"画了半个图想先存一下"会被拒，编辑中途无法保存
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getOperatorId()).isNull();
        // 服务端重新发号（uk_wstep_step_id 是全表唯一，客户端键不能落库）
        assertThat(vo.getSteps().get(0).getStepId()).isEqualTo("WFS-0001-01-01");
        assertThat(vo.getStepCount()).isEqualTo(1);
        assertThat(vo.getVersionId()).isEqualTo("WFV-0001-01");
        assertThat(vo.getWorkflowId()).isEqualTo("WF-0001");
        assertThat(vo.getHasDraftChanges()).isTrue();
    }

    @Test
    void 保存草稿_空图_可存下() {
        givenDraftVersion("WFV-0001-01", "v1");

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01", request(List.of(), List.of()));

        // docs/07 §9.2 的 10 条规则都不覆盖"一个步骤都没有"（O-23）：保存阶段不报错
        assertThat(vo.getSteps()).isEmpty();
        assertThat(vo.getStepCount()).isZero();
    }

    @Test
    void 保存草稿_步骤重名_42213且带errors明细且不落库() {
        givenDraftVersion("WFV-0001-01", "v1");

        assertThatThrownBy(() -> service.saveDraft("WFV-0001-01",
                request(List.of(step("s1", "抽取"), step("s2", "抽取")), List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getErrorCode().getCode()).isEqualTo(42213);
                    // 前端靠 rule + step_name 把错误挂到画布节点上，而不是解析中文
                    List<DagViolation> errors = errorsOf(biz);
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).rule()).isEqualTo("10");
                    assertThat(errors.get(0).stepName()).isEqualTo("抽取");
                });
        // 校验必须先于"整包替换"：否则一次非法保存就把已存的图删了
        verify(stepMapper, never()).deleteByVersionId(any());
        verify(stepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void 保存草稿_存在环_42213并给出环路径() {
        givenDraftVersion("WFV-0001-01", "v1");
        // d 是唯一入口（可达性规则 1 通过），a↔b 成环 → 只有规则 5 命中
        var steps = List.of(step("d", "入口"), step("a", "A"), step("b", "B"));
        var edges = List.of(edge("d", "a"), edge("a", "b"), edge("b", "a"));

        assertThatThrownBy(() -> service.saveDraft("WFV-0001-01", request(steps, edges)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("循环依赖")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42213));
    }

    @Test
    void 保存草稿_存在不可达步骤_42213() {
        givenDraftVersion("WFV-0001-01", "v1");
        // a→b 是正常的一段；c↔d 自成闭环、没有任何入口 —— 无环图里"不可达"是不可能出现的
        // （入度为 0 的孤立节点本身就是入口），故不可达只能长这样。
        // 规则 1 先于规则 5 输出，所以这里拿到的是"不可达"而不是"循环依赖"：
        // 规则顺序 = 错误列表顺序，正是 dagInvalid 取首条定错误码所依赖的契约。
        var steps = List.of(step("a", "A"), step("b", "B"), step("c", "C"), step("d", "D"));
        var edges = List.of(edge("a", "b"), edge("c", "d"), edge("d", "c"));

        assertThatThrownBy(() -> service.saveDraft("WFV-0001-01", request(steps, edges)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可达")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42213));
    }

    @Test
    void 保存草稿_连线端点不存在_40001_且不删旧图() {
        givenDraftVersion("WFV-0001-01", "v1");

        assertThatThrownBy(() -> service.saveDraft("WFV-0001-01",
                request(List.of(step("s1", "A")), List.of(edge("s1", "sX")))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40001));
        // 请求体读不成一张图 → 40001，而不是编一条不存在的规则号塞进 42213
        verify(stepMapper, never()).deleteByVersionId(any());
    }

    @Test
    void 保存草稿_非草稿版本_42212() {
        WorkflowVersion published = version(31L, "WFV-0001-01", "v1", "PUBLISHED");
        versionRows.put(31L, published);
        when(versionMapper.selectOne(any())).thenReturn(published);
        when(workflowMapper.selectById(11L)).thenReturn(workflow());

        assertThatThrownBy(() -> service.saveDraft("WFV-0001-01", request(List.of(), List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42212));
        verify(stepMapper, never()).deleteByVersionId(any());
    }

    // ── 保存草稿：整包替换与发号 ─────────────────────────────

    @Test
    void 保存草稿_整包替换顺序_删边_删步骤_插步骤_插边() {
        givenDraftVersion("WFV-0001-01", "v1");

        service.saveDraft("WFV-0001-01",
                request(List.of(step("s1", "A"), step("s2", "B")), List.of(edge("s1", "s2"))));

        // 顺序是死的：反了会在插边时撞 FK（引用到刚被删掉的步骤主键）
        var order = inOrder(edgeMapper, stepMapper);
        order.verify(edgeMapper).deleteByVersionId(31L);
        order.verify(stepMapper).deleteByVersionId(31L);
        order.verify(stepMapper, times(2)).insert(any(WorkflowStep.class));
        order.verify(edgeMapper).insert(any(WorkflowEdge.class));
    }

    @Test
    void 保存草稿_覆盖旧图_库里不留上一次的行() {
        givenDraftVersion("WFV-0001-01", "v1");
        stepRows.add(storedStep(9001L, 31L, "WFS-0001-01-01", "旧步骤"));
        edgeRows.add(storedEdge(9002L, 31L, 9001L, 9001L + 1));

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01",
                request(List.of(step("s1", "新步骤")), List.of()));

        assertThat(stepRows).hasSize(1);
        assertThat(stepRows.get(0).getStepName()).isEqualTo("新步骤");
        assertThat(edgeRows).isEmpty();
        assertThat(vo.getSteps()).extracting(DagStepDef::getStepName).containsExactly("新步骤");
    }

    @Test
    void 保存草稿_请求侧内部键只作锚点_连线端点落库是新步骤主键() {
        givenDraftVersion("WFV-0001-01", "v1");

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01",
                request(List.of(step("s1", "抽取"), step("s2", "装载")), List.of(edge("s1", "s2"))));

        WorkflowStep first = stepRows.get(0);
        WorkflowStep second = stepRows.get(1);
        assertThat(edgeRows.get(0).getSourceStepId()).isEqualTo(first.getId());
        assertThat(edgeRows.get(0).getTargetStepId()).isEqualTo(second.getId());
        // 回读时端点翻回服务端编号，请求里的 s1/s2 不出现在响应里
        assertThat(vo.getEdges()).hasSize(1);
        assertThat(vo.getEdges().get(0).getSourceStepId()).isEqualTo("WFS-0001-01-01");
        assertThat(vo.getEdges().get(0).getTargetStepId()).isEqualTo("WFS-0001-01-02");
    }

    @Test
    void 保存草稿_外键业务编号翻内部主键_解析不到写null而不是哨兵() {
        givenDraftVersion("WFV-0001-01", "v1");
        Operator operator = new Operator();
        operator.setId(66L);
        operator.setOperatorId("OP-0001");
        OperatorVersion operatorVersion = new OperatorVersion();
        operatorVersion.setId(77L);
        operatorVersion.setVersionId("OPV-0001-01");
        when(operatorMapper.selectList(any())).thenReturn(List.of(operator));
        when(operatorVersionMapper.selectList(any())).thenReturn(List.of(operatorVersion));

        DagStepDef def = step("s1", "抽取");
        def.setOperatorId("OP-0001");
        def.setOperatorVersionId("OPV-0001-01");
        def.setTargetClusterId("CL-9999");        // 库里查不到
        service.saveDraft("WFV-0001-01", request(List.of(def), List.of()));

        WorkflowStep persisted = stepRows.get(0);
        assertThat(persisted.getOperatorId()).isEqualTo(66L);
        assertThat(persisted.getOperatorVersionId()).isEqualTo(77L);
        // FK 列可空；写哨兵 -1 会当场撞外键约束
        assertThat(persisted.getTargetClusterId()).isNull();
    }

    @Test
    void 保存草稿_工作流参数与画布尺寸落库() {
        givenDraftVersion("WFV-0001-01", "v1");
        SaveWorkflowVersionRequest request = request(List.of(), List.of());
        request.setWorkflowParams(List.of(Map.of("name", "biz_date", "default", "2026-10-07")));
        request.setCanvasWidth(1600);
        request.setCanvasHeight(900);

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01", request);

        assertThat(vo.getWorkflowParams()).hasSize(1);
        assertThat(vo.getWorkflowParams().get(0)).containsEntry("name", "biz_date");
        assertThat(vo.getCanvasWidth()).isEqualTo(1600);
        assertThat(vo.getCanvasHeight()).isEqualTo(900);
    }

    @Test
    void 保存草稿_步骤参数JSONB来回一趟不丢内容() {
        givenDraftVersion("WFV-0001-01", "v1");
        DagStepDef def = step("s1", "抽取");
        def.setParams(Map.of("sql", "${project.code}", "limit", 100));
        def.setCustomParams(Map.of("note", "临时"));

        WorkflowVersionVO vo = service.saveDraft("WFV-0001-01", request(List.of(def), List.of()));

        // params 落库是 JSONB 字符串；读回时必须解析成 Map，否则规则 3/4 会静默失效
        assertThat(vo.getSteps().get(0).getParams()).containsEntry("sql", "${project.code}");
        assertThat(vo.getSteps().get(0).getCustomParams()).containsEntry("note", "临时");
    }

    // ── 新开草稿 ────────────────────────────────────────────

    @Test
    void 新开草稿_已有未发布草稿_42215() {
        Workflow workflow = workflow();
        workflow.setHasDraftChanges(true);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        assertThatThrownBy(() -> service.createDraft("WF-0001"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42215));
        verify(versionMapper, never()).insert(any(WorkflowVersion.class));
    }

    @Test
    void 新开草稿_复制当前版本整张图并重新发号() {
        Workflow workflow = workflow();
        workflow.setCurrentVersionId(31L);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        versionRows.put(31L, version(31L, "WFV-0001-01", "v1", "PUBLISHED"));
        stepRows.add(storedStep(101L, 31L, "WFS-0001-01-01", "抽取"));
        stepRows.add(storedStep(102L, 31L, "WFS-0001-01-02", "装载"));
        edgeRows.add(storedEdge(201L, 31L, 101L, 102L));
        when(versionMapper.selectMaxVersionIndex(11L)).thenReturn(1);

        WorkflowVersionVO vo = service.createDraft("WF-0001");

        assertThat(vo.getVersionId()).isEqualTo("WFV-0001-02");
        assertThat(vo.getVersionNo()).isEqualTo("v2");
        assertThat(vo.getPublishStatus()).isEqualTo("DRAFT");
        // 新版本号 ⇒ step_id 必然不同（全表唯一索引），必须按位置重新发号
        assertThat(vo.getSteps()).extracting(DagStepDef::getStepId)
                .containsExactly("WFS-0001-02-01", "WFS-0001-02-02");
        assertThat(vo.getEdges()).hasSize(1);
        assertThat(vo.getEdges().get(0).getSourceStepId()).isEqualTo("WFS-0001-02-01");
        assertThat(vo.getEdges().get(0).getTargetStepId()).isEqualTo("WFS-0001-02-02");
        assertThat(vo.getStepCount()).isEqualTo(2);
        // 基础版本的行不能被改动
        assertThat(stepRows).hasSize(4);
        assertThat(workflow.getHasDraftChanges()).isTrue();
    }

    @Test
    void 新开草稿_从未发布过_也能建出一张空图() {
        Workflow workflow = workflow();
        workflow.setCurrentVersionId(null);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(versionMapper.selectMaxVersionIndex(11L)).thenReturn(null);

        WorkflowVersionVO vo = service.createDraft("WF-0001");

        // 否则新建完就卡死：没有草稿 → 发布不了 → 永远出不了第一个版本
        assertThat(vo.getVersionId()).isEqualTo("WFV-0001-01");
        assertThat(vo.getSteps()).isEmpty();
        assertThat(workflow.getHasDraftChanges()).isTrue();
    }

    @Test
    void 新开草稿_版本序号含软删行_不重复发号() {
        Workflow workflow = workflow();
        workflow.setCurrentVersionId(null);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        // v3 已软删但仍占着 version_id 的全表唯一索引 → 下一个必须是 v4
        when(versionMapper.selectMaxVersionIndex(11L)).thenReturn(3);

        assertThat(service.createDraft("WF-0001").getVersionId()).isEqualTo("WFV-0001-04");
    }

    // ── 版本详情 ────────────────────────────────────────────

    @Test
    void 版本详情_外键翻回业务编号() {
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "PUBLISHED");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        when(workflowMapper.selectById(11L)).thenReturn(workflow());

        WorkflowStep step = storedStep(101L, 31L, "WFS-0001-01-01", "抽取");
        step.setOperatorId(66L);
        step.setOperatorVersionId(77L);
        step.setParams("{\"sql\":\"select 1\"}");
        stepRows.add(step);
        Operator operator = new Operator();
        operator.setId(66L);
        operator.setOperatorId("OP-0001");
        OperatorVersion operatorVersion = new OperatorVersion();
        operatorVersion.setId(77L);
        operatorVersion.setVersionId("OPV-0001-01");
        when(operatorMapper.selectBatchIds(any())).thenReturn(List.of(operator));
        when(operatorVersionMapper.selectBatchIds(any())).thenReturn(List.of(operatorVersion));

        WorkflowVersionVO vo = service.get("WFV-0001-01");

        // D-27：出网一律业务编号，内部 bigint 主键不出网
        assertThat(vo.getSteps().get(0).getOperatorId()).isEqualTo("OP-0001");
        assertThat(vo.getSteps().get(0).getOperatorVersionId()).isEqualTo("OPV-0001-01");
        assertThat(vo.getSteps().get(0).getParams()).containsEntry("sql", "select 1");
        assertThat(vo.getWorkflowId()).isEqualTo("WF-0001");
        assertThat(vo.getWorkflowName()).isEqualTo("日增量清算");
        assertThat(vo.getHasDraftChanges()).isFalse();
    }

    @Test
    void 版本详情_悬挂连线_跳过而不报错() {
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "DRAFT");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        when(workflowMapper.selectById(11L)).thenReturn(workflow());
        stepRows.add(storedStep(101L, 31L, "WFS-0001-01-01", "抽取"));
        edgeRows.add(storedEdge(201L, 31L, 101L, 999L));   // 终点已不存在

        WorkflowVersionVO vo = service.get("WFV-0001-01");

        // 历史脏数据不该让"打开画布"整个失败
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getEdges()).isEmpty();
    }

    @Test
    void 版本详情_跨项目_40301() {
        when(versionMapper.selectOne(any())).thenReturn(version(31L, "WFV-0001-01", "v1", "DRAFT"));
        // 带行级过滤查不到；无过滤探测能查到 → 存在但越权
        when(workflowMapper.selectById(11L)).thenReturn(null, workflow());

        assertThatThrownBy(() -> service.get("WFV-0001-01"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40301));
    }

    @Test
    void 版本详情_不存在_40400() {
        when(versionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get("WFV-9999-01"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40400));
    }

    // ── 发布 ────────────────────────────────────────────────

    @Test
    void 发布_全量校验_校验对象是库内数据而非请求() {
        Workflow workflow = workflow();
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "DRAFT");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        // 库里躺着一个没绑算子的步骤：请求侧早返回了，只有读库才能发现
        stepRows.add(storedStep(101L, 31L, "WFS-0001-01-01", "抽取"));

        assertThatThrownBy(() -> service.publishVersion(workflow, "WFV-0001-01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未选择算子")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42213));
        verify(versionMapper, never()).updateById(any(WorkflowVersion.class));
    }

    @Test
    void 发布_引用未发布算子版本_42218而不是42213() {
        Workflow workflow = workflow();
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "DRAFT");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        WorkflowStep step = storedStep(101L, 31L, "WFS-0001-01-01", "抽取");
        step.setOperatorId(66L);
        step.setOperatorVersionId(77L);
        stepRows.add(step);
        OperatorVersion unpublished = new OperatorVersion();
        unpublished.setId(77L);
        unpublished.setVersionId("OPV-0001-01");
        unpublished.setPublishStatus("DRAFT");
        when(operatorVersionMapper.selectBatchIds(any())).thenReturn(List.of(unpublished));

        // "选了但那一版没发布"与"压根没选"是两种事实，必须回不同的码
        assertThatThrownBy(() -> service.publishVersion(workflow, "WFV-0001-01"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(42218));
        verify(versionMapper, never()).updateById(any(WorkflowVersion.class));
    }

    @Test
    void 发布_全量通过_冻结版本并记录发布人() {
        Workflow workflow = workflow();
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "DRAFT");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);
        WorkflowStep step = storedStep(101L, 31L, "WFS-0001-01-01", "抽取");
        step.setOperatorId(66L);
        step.setOperatorVersionId(77L);
        stepRows.add(step);
        OperatorVersion published = new OperatorVersion();
        published.setId(77L);
        published.setVersionId("OPV-0001-01");
        published.setPublishStatus("PUBLISHED");
        when(operatorVersionMapper.selectBatchIds(any())).thenReturn(List.of(published));

        WorkflowVersion result = service.publishVersion(workflow, "WFV-0001-01");

        assertThat(result.getPublishStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getPublisher()).isEqualTo("system");   // 无登录上下文时的兜底
        assertThat(result.getPublishedAt()).isNotNull();
        verify(versionMapper).updateById(version);
    }

    @Test
    void 发布_版本已是PUBLISHED_只切指针不覆盖发布人() {
        Workflow workflow = workflow();
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "PUBLISHED");
        version.setPublisher("alice");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);

        WorkflowVersion result = service.publishVersion(workflow, "WFV-0001-01");

        // 回滚场景：只把历史版本切回"当前版本"。覆盖发布人会让审计回答不了"当时是谁发的"
        assertThat(result.getPublisher()).isEqualTo("alice");
        verify(versionMapper, never()).updateById(any(WorkflowVersion.class));
        verify(stepMapper, never()).listByVersionId(any());
    }

    @Test
    void 发布_已归档版本_40900() {
        Workflow workflow = workflow();
        WorkflowVersion version = version(31L, "WFV-0001-01", "v1", "ARCHIVED");
        versionRows.put(31L, version);
        when(versionMapper.selectOne(any())).thenReturn(version);

        assertThatThrownBy(() -> service.publishVersion(workflow, "WFV-0001-01"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40900));
    }

    @Test
    void 发布_版本不属于该工作流_40400() {
        when(versionMapper.selectOne(any())).thenReturn(null);

        // 不泄漏"这个版本号在别处存在"
        assertThatThrownBy(() -> service.publishVersion(workflow(), "WFV-9999-01"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40400));
    }

    // ── 装配器契约（借服务路径顺带验一遍）──────────────────────

    @Test
    void 保存草稿_外键解析走批量IN查询_查询次数与节点数解耦() {
        givenDraftVersion("WFV-0001-01", "v1");
        OperatorVersion operatorVersion = new OperatorVersion();
        operatorVersion.setId(77L);
        operatorVersion.setVersionId("OPV-0001-01");
        when(operatorVersionMapper.selectList(any())).thenReturn(List.of(operatorVersion));
        Cluster cluster = new Cluster();
        cluster.setId(88L);
        cluster.setClusterId("CL-0001");
        when(clusterMapper.selectList(any())).thenReturn(List.of(cluster));

        List<DagStepDef> steps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            DagStepDef def = step("s" + i, "步骤" + i);
            def.setOperatorVersionId("OPV-0001-01");
            def.setTargetClusterId("CL-0001");
            steps.add(def);
        }
        service.saveDraft("WFV-0001-01", request(steps, List.of()));

        // 20 个节点 → 每类外键仍只查 1 次（画布保存是最高频写路径，N+1 会直接吃掉体验）
        verify(operatorVersionMapper, times(1)).selectList(any());
        verify(clusterMapper, times(1)).selectList(any());
    }
}
