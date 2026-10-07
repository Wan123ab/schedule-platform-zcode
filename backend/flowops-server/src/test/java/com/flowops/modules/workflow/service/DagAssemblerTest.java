package com.flowops.modules.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorVersion;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DAG 装配器单测（docs/07 §9.2 的"取数"侧）。
 *
 * <p>重点三条：</p>
 * <ol>
 *   <li><b>40001 vs 42213 的边界</b>：请求体读不成一张图（键重复/端点缺失/自环）走 40001，
 *       因为这些都要往 {@code errors[].rule} 里塞一个规则号，而规则号是前后端共用合约；
 *       不能为了报错凭空发明一个 <code>"1.5"</code>。</li>
 *   <li><b>哨兵 vs null</b>：{@code null}=用户没选、{@code -1}=用户选了但解析不到。
 *       两者对应不同错误码，压成一个就会给出指向错误方向的提示。</li>
 *   <li><b>两条装配路径不合并</b>：请求侧要翻译业务编号、实体侧不需要 ——
 *       合并必然要么多打一次查询、要么在实体路径上做无意义的字符串匹配。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DagAssemblerTest {

    @Mock private OperatorMapper operatorMapper;
    @Mock private OperatorVersionMapper operatorVersionMapper;
    @Mock private OperatorParamDefMapper paramDefMapper;
    @Mock private OperatorOutputDeclMapper outputDeclMapper;
    @Mock private ClusterMapper clusterMapper;
    @Mock private QueueMapper queueMapper;

    private DagAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new DagAssembler(operatorMapper, operatorVersionMapper, paramDefMapper,
                outputDeclMapper, clusterMapper, queueMapper, new ObjectMapper());
    }

    // ── 夹具 ────────────────────────────────────────────────

    private DagStepDef def(String key, String name) {
        DagStepDef def = new DagStepDef();
        def.setStepId(key);
        def.setStepName(name);
        def.setStepType("TASK");
        return def;
    }

    private DagEdgeDef edge(String from, String to) {
        DagEdgeDef edge = new DagEdgeDef();
        edge.setSourceStepId(from);
        edge.setTargetStepId(to);
        return edge;
    }

    private WorkflowStep entityStep(Long id, String name) {
        WorkflowStep step = new WorkflowStep();
        step.setId(id);
        step.setStepName(name);
        step.setStepType("TASK");
        return step;
    }

    private static int codeOf(Throwable e) {
        return ((BizException) e).getErrorCode().getCode();
    }

    // ── 1. 请求体自身完整性 → 40001 ─────────────────────────

    @Test
    void 步骤内部键重复_40001() {
        assertThatThrownBy(() -> assembler.indexByKey(List.of(def("s1", "A"), def("s1", "B")), List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("步骤内部键重复")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40001));
    }

    @Test
    void 连线端点指向不存在的步骤_40001() {
        assertThatThrownBy(() -> assembler.indexByKey(List.of(def("s1", "A")), List.of(edge("s1", "sX"))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40001));
    }

    @Test
    void 自环_40001_而不是留给DB的CHECK约束() {
        // DDL 的 ck_edge_no_self_loop 也会拦，但那时报的是 50001（500）
        assertThatThrownBy(() -> assembler.indexByKey(List.of(def("s1", "A")), List.of(edge("s1", "s1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能指向自身")
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(40001));
    }

    @Test
    void 合法图_返回键到下标且与连线下标对一致() {
        var steps = List.of(def("s1", "A"), def("s2", "B"), def("s3", "C"));
        var edges = List.of(edge("s2", "s3"), edge("s1", "s2"));

        Map<String, Integer> index = assembler.indexByKey(steps, edges);
        var links = assembler.toEdgeLinks(edges, index);

        assertThat(index).containsEntry("s1", 0).containsEntry("s2", 1).containsEntry("s3", 2);
        // 顺序跟着请求走，不重排：重排会让"保存后的表内容"与"提交的图"顺序不一致
        assertThat(links).containsExactly(
                new DagValidationContext.EdgeLink(1, 2),
                new DagValidationContext.EdgeLink(0, 1));
    }

    // ── 2. 哨兵 vs null ────────────────────────────────────

    @Test
    void 解析不到的业务编号_校验用哨兵_落库用null_没填才是null() {
        Operator resolvable = new Operator();
        resolvable.setId(66L);
        resolvable.setOperatorId("OP-0001");
        when(operatorMapper.selectList(any())).thenReturn(List.of(resolvable));

        DagStepDef picked = def("s1", "A");
        picked.setOperatorId("OP-0001");
        DagStepDef stale = def("s2", "B");
        stale.setOperatorId("OP-9999");            // 填了，但那一行已不存在
        DagStepDef blank = def("s3", "C");         // 压根没填

        var resolution = assembler.resolve(List.of(picked, stale, blank));

        assertThat(resolution.idOf(resolution.operators(), "OP-0001")).isEqualTo(66L);
        // 落库路径：FK 列可空，写 -1 会撞外键约束
        assertThat(resolution.idOf(resolution.operators(), "OP-9999")).isNull();
        assertThat(resolution.idOf(resolution.operators(), null)).isNull();
        // 校验路径："选了但查不到"必须区别于"没选"，才能回 42218 而不是"你没选算子"
        assertThat(resolution.idOrSentinel(resolution.operators(), "OP-9999"))
                .isEqualTo(DagValidationContext.UNRESOLVED_ID);
        assertThat(resolution.idOrSentinel(resolution.operators(), "OP-0001")).isEqualTo(66L);
        assertThat(resolution.idOrSentinel(resolution.operators(), null)).isNull();
    }

    @Test
    void 空集合不发起IN查询() {
        // 所有步骤都没选算子/集群是常态（刚新建的空图），IN () 是非法 SQL
        assembler.resolve(List.of(def("s1", "A"), def("s2", "B")));
        assembler.toBusinessIds(Set.of(), Set.of(), Set.of(), Set.of());

        verify(operatorMapper, never()).selectList(any());
        verify(operatorMapper, never()).selectBatchIds(any());
        verify(clusterMapper, never()).selectBatchIds(any());
    }

    @Test
    void 查不到的行不进反向映射_不把内部主键当业务编号返回() {
        // 66 查得到、99 查不到
        Operator operator = new Operator();
        operator.setId(66L);
        operator.setOperatorId("OP-0001");
        when(operatorMapper.selectBatchIds(any())).thenReturn(List.of(operator));

        var refs = assembler.toBusinessIds(Set.of(66L, 99L), Set.of(), Set.of(), Set.of());

        assertThat(refs.of(refs.operators(), 66L)).isEqualTo("OP-0001");
        // 若这里返回 "99"，前端会把它当成一个真实编号去引用
        assertThat(refs.of(refs.operators(), 99L)).isNull();
        assertThat(refs.of(refs.operators(), null)).isNull();
    }

    // ── 3. 两条装配路径 ────────────────────────────────────

    @Test
    void 请求路径_full时查跨域规格_half时不查() {
        OperatorVersion version = new OperatorVersion();
        version.setId(77L);
        version.setVersionId("OPV-0001-01");
        version.setPublishStatus("PUBLISHED");
        when(operatorVersionMapper.selectList(any())).thenReturn(List.of(version));
        // 两条不同的查询路径：resolve 走 selectList（按业务编号），loadOperatorSpecs 走
        // selectBatchIds（按内部主键）—— 只桩前者会让 full 模式的规格表是空的
        when(operatorVersionMapper.selectBatchIds(any())).thenReturn(List.of(version));

        DagStepDef def = def("s1", "A");
        def.setOperatorVersionId("OPV-0001-01");
        var steps = List.of(def);
        var resolution = assembler.resolve(steps);
        var links = assembler.toEdgeLinks(List.of(), assembler.indexByKey(steps, List.of()));

        var structural = assembler.buildRequestContext(steps, links, "FORBID", 1, resolution, false);
        assertThat(structural.operatorSpecs()).isEmpty();
        assertThat(structural.clusterSpecs()).isEmpty();
        // 结构校验只跑规则 1/5/10，不需要算子规格 —— 这一步省下的是编辑期最高频写操作的查询
        verify(operatorVersionMapper, never()).selectBatchIds(any());

        var full = assembler.buildRequestContext(steps, links, "FORBID", 1, resolution, true);
        assertThat(full.operatorSpecs()).containsKey(77L);
        assertThat(full.operatorSpecs().get(77L).publishStatus()).isEqualTo("PUBLISHED");
        verify(operatorVersionMapper).selectBatchIds(any());
    }

    @Test
    void 实体路径_外键已是内部主键_不再翻译且按行主键映射边() {
        WorkflowStep first = entityStep(101L, "A");
        first.setParams("{\"k\":\"v\"}");
        WorkflowStep second = entityStep(102L, "B");
        second.setOperatorId(66L);
        second.setOperatorVersionId(77L);

        WorkflowEdge link = new WorkflowEdge();
        link.setSourceStepId(101L);
        link.setTargetStepId(102L);
        WorkflowEdge dangling = new WorkflowEdge();
        dangling.setSourceStepId(101L);
        dangling.setTargetStepId(999L);      // 已不存在的步骤行

        var ctx = assembler.buildEntityContext(List.of(first, second), List.of(link, dangling), "QUEUE", 3);

        assertThat(ctx.steps()).hasSize(2);
        assertThat(ctx.steps().get(0).stepName()).isEqualTo("A");
        // 实体侧不做任何反向翻译：77 就是内部主键，原样进上下文
        assertThat(ctx.steps().get(1).operatorVersionId()).isEqualTo(77L);
        // params 是 JSONB 字符串，必须解析回 Map：否则规则 3/4 会静默失效
        assertThat(ctx.steps().get(0).params()).containsEntry("k", "v");
        assertThat(ctx.edges()).hasSize(1);
        assertThat(ctx.edges().get(0).sourceIndex()).isZero();
        assertThat(ctx.edges().get(0).targetIndex()).isEqualTo(1);
        assertThat(ctx.concurrency().policy()).isEqualTo("QUEUE");
        assertThat(ctx.concurrency().maxParallelRuns()).isEqualTo(3);
    }

    @Test
    void 实体路径_参数是坏JSON_按空对象参与校验而不是抛异常() {
        WorkflowStep broken = entityStep(101L, "A");
        broken.setParams("{不是 JSON");

        var ctx = assembler.buildEntityContext(List.of(broken), List.of(), "FORBID", 1);

        // 坏 JSON 按空对象继续 → 规则 3 会响亮地报"缺少必填参数"，而不是悄悄放过
        assertThat(ctx.steps().get(0).params()).isEmpty();
    }

    @Test
    void 实体路径_无依赖时_空上下文也能量出来() {
        var ctx = assembler.buildEntityContext(List.of(), List.of(), "ALLOW", 2);

        assertThat(ctx.steps()).isEmpty();
        assertThat(ctx.edges()).isEmpty();
        assertThat(ctx.operatorSpecs()).isEmpty();
        assertThat(ctx.clusterSpecs()).isEmpty();
        verify(operatorVersionMapper, never()).selectBatchIds(any());
    }
}
