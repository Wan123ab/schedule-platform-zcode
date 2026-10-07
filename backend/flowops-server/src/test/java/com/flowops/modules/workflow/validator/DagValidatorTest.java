package com.flowops.modules.workflow.validator;

import com.flowops.modules.workflow.validator.DagValidationContext.ClusterSpec;
import com.flowops.modules.workflow.validator.DagValidationContext.Concurrency;
import com.flowops.modules.workflow.validator.DagValidationContext.EdgeLink;
import com.flowops.modules.workflow.validator.DagValidationContext.OperatorSpec;
import com.flowops.modules.workflow.validator.DagValidationContext.ResourceLimit;
import com.flowops.modules.workflow.validator.DagValidationContext.StepNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAG 校验器单测 —— docs/09 §M3 的 DoD 明确要求「8 条规则<b>逐条</b>造错误用例」，
 * 故本类按规则编号组织：每个规则至少有一个"该报"的用例，关键规则另有"不该报"的反例。
 *
 * <p><b>为什么用构造上下文而不是 mock</b>：校验器是纯函数，输入是
 * {@link DagValidationContext}。用 mock 拼输入只会让"造一条环"变成"拼一堆 when"，
 * 而真正的边界（下标越界、空集合、null 上限）反而更难构造。</p>
 */
class DagValidatorTest {

    private final DagValidator validator = new DagValidator();

    // ── 时机：保存草稿只跑规则 1/5/10 ─────────────────────────

    @Test
    void 保存草稿_只报结构类规则_不报算人与并发() {
        // 一个"缺算子、缺必填参数、没并发配置"的图 —— 结构上完全合法
        DagValidationContext ctx = ctx(
                List.of(step("A", "TASK", null, null), step("B", "TASK", null, null)),
                List.of(edge(0, 1)),
                null, Map.of(), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.STRUCTURAL)).isEmpty();
        // 同一份图在发布时要被全量拦住
        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .extracting(DagViolation::rule)
                .contains("2", "8");
    }

    @Test
    void 保存草稿_重名与环照样拦() {
        DagValidationContext ctx = ctx(
                List.of(step("A", "TASK", 1L, 11L), step("A", "TASK", 1L, 11L)),
                List.of(edge(0, 1), edge(1, 0)),
                new Concurrency("FORBID", 1), Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.STRUCTURAL))
                .extracting(DagViolation::rule)
                .contains("5", "10");
    }

    // ── 规则 1：入口 + 可达性 ────────────────────────────────

    @Test
    void 规则1_不可达步骤被报出且备注除外() {
        // ⚠️ 构造"不可达"必须借助环：只要图是无环的，任何入度 > 0 的节点都必然
        // 从某个入度 0 的节点可达 —— 也就是说"不可达"与规则 5 天然同源。
        // 这里 S→T 正常成链，A↔B 成环且没有入口喂它，备注 N 挂在环上。
        List<StepNode> steps = List.of(
                step("S", "TASK", 1L, 11L), step("T", "TASK", 1L, 11L),
                step("A", "TASK", 1L, 11L), step("B", "TASK", 1L, 11L),
                step("随手记", "NOTE", null, null));
        DagValidationContext ctx = publishCtx(steps,
                List.of(edge(0, 1), edge(2, 3), edge(3, 2), edge(3, 4)));

        List<DagViolation> violations = validator.validate(ctx, DagValidator.Phase.PUBLISH);

        assertThat(violations)
                .filteredOn(v -> "1".equals(v.rule()))
                .extracting(DagViolation::message)
                .containsExactly("步骤「A」不可达", "步骤「B」不可达");
    }

    @Test
    void 规则1_没有任何入度0步骤() {
        DagValidationContext ctx = publishCtx(
                List.of(step("A", "TASK", 1L, 11L), step("B", "TASK", 1L, 11L)),
                List.of(edge(0, 1), edge(1, 0)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).contains("没有任何入口步骤"));
    }

    // ── 规则 2：必须绑算子 ──────────────────────────────────

    @Test
    void 规则2_未选算子或算子版本() {
        DagValidationContext ctx = publishCtx(
                List.of(step("A", "TASK", null, null), step("B", "TASK", 1L, null)),
                List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .filteredOn(v -> "2".equals(v.rule()))
                .extracting(DagViolation::message)
                .containsExactly("步骤「A」未选择算子或算子版本", "步骤「B」未选择算子或算子版本");
    }

    // ── 规则 3：必填参数 ────────────────────────────────────

    @Test
    void 规则3_缺必填参数_空白字符串也算缺() {
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("input_path", "  "));
        DagValidationContext ctx = publishCtx(List.of(a), List.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("3");
                    assertThat(v.message()).isEqualTo("步骤「A」缺少必填参数「input_path」");
                });
    }

    @Test
    void 规则3_参数齐全_不报() {
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("input_path", "/data/x"));
        DagValidationContext ctx = publishCtx(List.of(a), List.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .noneSatisfy(v -> assertThat(v.rule()).isEqualTo("3"));
    }

    // ── 规则 4：变量引用 ────────────────────────────────────

    @Test
    void 规则4_引用不存在的步骤() {
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("p", "${step.不存在.output.x}"));
        DagValidationContext ctx = publishCtx(List.of(a), List.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.errorCode()).isEqualTo(DagViolation.CODE_VARIABLE);
                    assertThat(v.message()).contains("引用了不存在的步骤「不存在」");
                });
    }

    @Test
    void 规则4_引用下游步骤的输出_不可达() {
        // A → B，但 A 的参数引用了 B 的输出（反向依赖）
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("p", "${step.B.output.x}"));
        StepNode b = step("B", "TASK", 1L, 11L);
        DagValidationContext ctx = publishCtx(List.of(a, b), List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.errorCode()).isEqualTo(DagViolation.CODE_VARIABLE);
                    assertThat(v.message()).isEqualTo("步骤「A」引用了不可达的变量 ${step.B.output.x}");
                });
    }

    @Test
    void 规则4_引用上游输出且已声明_通过() {
        StepNode a = step("A", "TASK", 1L, 11L);
        StepNode b = withParams(step("B", "TASK", 1L, 11L), Map.of("p", "${step.A.output.row_count}"));
        DagValidationContext ctx = publishCtx(List.of(a, b), List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .noneSatisfy(v -> assertThat(v.errorCode()).isEqualTo(DagViolation.CODE_VARIABLE));
    }

    @Test
    void 规则4_引用嵌套数组里的变量也能被发现() {
        // 参数是 JSONB 结构，引用藏在数组里 —— 只扫顶层字符串会漏（这正是 parseParams 的存在理由）
        StepNode a = step("A", "TASK", 1L, 11L);
        StepNode b = withParams(step("B", "TASK", 1L, 11L),
                Map.of("args", List.of("--in", "${step.不存在.output.x}")));
        DagValidationContext ctx = publishCtx(List.of(a, b), List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).contains("不存在的步骤「不存在」"));
    }

    @Test
    void 规则4_引用了上游未声明的输出() {
        StepNode a = step("A", "TASK", 1L, 11L);
        StepNode b = withParams(step("B", "TASK", 1L, 11L), Map.of("p", "${step.A.output.typo}"));
        DagValidationContext ctx = publishCtx(List.of(a, b), List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).isEqualTo("步骤「B」引用了步骤「A」未声明的输出「typo」"));
    }

    @Test
    void 规则4_语法错误也归到变量类错误码() {
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("p", "${step.A.output.x"));
        DagValidationContext ctx = publishCtx(List.of(a), List.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.errorCode()).isEqualTo(DagViolation.CODE_VARIABLE);
                    assertThat(v.message()).contains("缺少 '}'");
                });
    }

    @Test
    void 规则4_引用自身输出() {
        StepNode a = withParams(step("A", "TASK", 1L, 11L), Map.of("p", "${step.A.output.x}"));
        DagValidationContext ctx = publishCtx(List.of(a), List.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).contains("不能引用自身的输出"));
    }

    // ── 规则 5：无环 ────────────────────────────────────────

    @Test
    void 规则5_三元环_提示里给出环路径() {
        DagValidationContext ctx = publishCtx(
                List.of(step("A", "TASK", 1L, 11L), step("B", "TASK", 1L, 11L), step("C", "TASK", 1L, 11L)),
                List.of(edge(0, 1), edge(1, 2), edge(2, 0)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("5");
                    assertThat(v.message()).contains("检测到循环依赖").contains("A → B → C");
                });
    }

    @Test
    void 规则5_菱形DAG不算环() {
        DagValidationContext ctx = publishCtx(
                List.of(step("A", "TASK", 1L, 11L), step("B", "TASK", 1L, 11L),
                        step("C", "TASK", 1L, 11L), step("D", "TASK", 1L, 11L)),
                List.of(edge(0, 1), edge(0, 2), edge(1, 3), edge(2, 3)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .noneSatisfy(v -> assertThat(v.rule()).isEqualTo("5"));
    }

    // ── 规则 6：资源不超集群上限 ─────────────────────────────

    @Test
    void 规则6_同集群步骤合计超cpu与内存上限() {
        StepNode a = withResource(step("A", "TASK", 1L, 11L), new BigDecimal("6.00"), 3000L);
        StepNode b = withResource(step("B", "TASK", 1L, 11L), new BigDecimal("6.00"), 3000L);
        DagValidationContext ctx = new DagValidationContext(
                List.of(a, b), List.of(edge(0, 1)), new Concurrency("FORBID", 1),
                Map.of(11L, published()),
                Map.of(7L, new ClusterSpec("CL-0001", "生产集群",
                        new ResourceLimit(new BigDecimal("8.00"), null, 4096L, null))));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .filteredOn(v -> "6".equals(v.rule()))
                .extracting(DagViolation::message)
                .containsExactly(
                        "步骤「A」等 2 个步骤在目标集群 生产集群（CL-0001） 上的 cpu 合计申请 12 / 上限 8",
                        "步骤「A」等 2 个步骤在目标集群 生产集群（CL-0001） 上的 内存(MB) 合计申请 6000 / 上限 4096");
    }

    @Test
    void 规则6_集群无上限数据_跳过而不是当成0() {
        StepNode a = withResource(step("A", "TASK", 1L, 11L), new BigDecimal("99.00"), 99999L);
        DagValidationContext ctx = new DagValidationContext(
                List.of(a), List.of(), new Concurrency("FORBID", 1),
                Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .noneSatisfy(v -> assertThat(v.rule()).isEqualTo("6"));
    }

    // ── 规则 7：算子版本必须已发布 ───────────────────────────

    @Test
    void 规则7_引用草稿态算子版本_回42218() {
        StepNode a = step("A", "TASK", 1L, 11L);
        DagValidationContext ctx = new DagValidationContext(
                List.of(a), List.of(), new Concurrency("FORBID", 1),
                Map.of(11L, new OperatorSpec("DRAFT", Set.of(), Set.of())), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("7");
                    assertThat(v.errorCode()).isEqualTo(DagViolation.CODE_UNPUBLISHED_OPERATOR);
                    assertThat(v.message()).isEqualTo("步骤「A」引用了未发布的算子版本");
                });
    }

    @Test
    void 规则7_算子版本不存在() {
        StepNode a = step("A", "TASK", 1L, 11L);
        DagValidationContext ctx = new DagValidationContext(
                List.of(a), List.of(), new Concurrency("FORBID", 1), Map.of(), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).contains("不存在或已被删除"));
    }

    // ── 规则 8：并发配置 ────────────────────────────────────

    @Test
    void 规则8_未声明并发配置与并行数非法() {
        DagValidationContext missing = ctx(List.of(step("A", "TASK", 1L, 11L)), List.of(), null,
                Map.of(11L, published()), Map.of());
        assertThat(validator.validate(missing, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("8");
                    assertThat(v.stepName()).isNull();
                    assertThat(v.message()).isEqualTo("工作流未声明并发控制配置");
                });

        DagValidationContext zero = ctx(List.of(step("A", "TASK", 1L, 11L)), List.of(),
                new Concurrency("ALLOW", 0), Map.of(11L, published()), Map.of());
        assertThat(validator.validate(zero, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).contains("最大并行数必须大于等于 1"));
    }

    @Test
    void 规则8_策略不在白名单() {
        DagValidationContext ctx = ctx(List.of(step("A", "TASK", 1L, 11L)), List.of(),
                new Concurrency("WHATEVER", 2), Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.message()).isEqualTo("工作流未声明并发控制配置"));
    }

    // ── 规则 9：重试上限 ────────────────────────────────────

    @Test
    void 规则9_重试超上限与负数() {
        StepNode over = withRetry(step("A", "TASK", 1L, 11L), 11);
        StepNode negative = withRetry(step("B", "TASK", 1L, 11L), -1);
        DagValidationContext ctx = ctx(List.of(over, negative), List.of(),
                new Concurrency("FORBID", 1), Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .filteredOn(v -> "9".equals(v.rule()))
                .extracting(DagViolation::message)
                .containsExactly("步骤「A」重试次数超过上限 10", "步骤「B」重试次数不能为负数");
    }

    @Test
    void 规则9_恰好等于上限_通过() {
        StepNode ok = withRetry(step("A", "TASK", 1L, 11L), DagValidator.MAX_RETRY);
        DagValidationContext ctx = ctx(List.of(ok), List.of(),
                new Concurrency("FORBID", 1), Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .noneSatisfy(v -> assertThat(v.rule()).isEqualTo("9"));
    }

    // ── 规则 10：步骤名唯一 ─────────────────────────────────

    @Test
    void 规则10_步骤名重复() {
        DagValidationContext ctx = publishCtx(
                List.of(step("清洗", "TASK", 1L, 11L), step("清洗", "TASK", 1L, 11L)),
                List.of(edge(0, 1)));

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("10");
                    assertThat(v.message()).isEqualTo("步骤名「清洗」重复");
                });
    }

    // ── 整体：一条干净的工作流 ──────────────────────────────

    @Test
    void 干净的五步工作流_全量校验通过() {
        List<StepNode> steps = List.of(
                withParams(step("抽取", "TASK", 1L, 11L), Map.of("input_path", "/data")),
                withParams(step("清洗", "TASK", 1L, 11L), Map.of("input_path", "${step.抽取.output.file_path}")),
                withParams(step("聚合", "TASK", 1L, 11L), Map.of("input_path", "${step.清洗.output.file_path}")),
                withParams(step("导出", "TASK", 1L, 11L), Map.of("input_path", "${step.聚合.output.file_path}")),
                step("说明", "NOTE", null, null));
        DagValidationContext ctx = new DagValidationContext(steps,
                List.of(edge(0, 1), edge(1, 2), edge(2, 3), edge(3, 4)),
                new Concurrency("QUEUE", 3),
                Map.of(11L, published()), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH)).isEmpty();
    }

    // ── 边界：越界边与空图 ──────────────────────────────────

    @Test
    void 越界的边被跳过_不抛数组越界() {
        DagValidationContext ctx = new DagValidationContext(
                List.of(step("A", "TASK", 1L, 11L)),
                List.of(new EdgeLink(0, 9), new EdgeLink(-1, 0)),
                new Concurrency("FORBID", 1), Map.of(11L, published()), Map.of());

        // 只要不抛异常即可：这里关心的是"用户点保存"路径上不会出现与他输入无关的崩溃
        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH)).isNotNull();
    }

    @Test
    void 空图_结构校验不报错但发布报规则8() {
        DagValidationContext ctx = new DagValidationContext(List.of(), List.of(), null, Map.of(), Map.of());

        assertThat(validator.validate(ctx, DagValidator.Phase.STRUCTURAL)).isEmpty();
        assertThat(validator.validate(ctx, DagValidator.Phase.PUBLISH))
                .anySatisfy(v -> assertThat(v.rule()).isEqualTo("8"));
    }

    // ── 夹具 ────────────────────────────────────────────────

    private static StepNode step(String name, String type, Long operatorId, Long operatorVersionId) {
        return new StepNode(name, type, operatorId, operatorVersionId, Map.of(), Map.of(),
                null, null, null, null, null, null);
    }

    private static StepNode withParams(StepNode base, Map<String, Object> params) {
        return new StepNode(base.stepName(), base.stepType(), base.operatorId(), base.operatorVersionId(),
                params, base.customParams(), base.targetClusterId(), base.cpu(), base.gpu(),
                base.memory(), base.disk(), base.retryCount());
    }

    private static StepNode withRetry(StepNode base, Integer retry) {
        return new StepNode(base.stepName(), base.stepType(), base.operatorId(), base.operatorVersionId(),
                base.params(), base.customParams(), base.targetClusterId(), base.cpu(), base.gpu(),
                base.memory(), base.disk(), retry);
    }

    /** 带资源与目标集群（集群 7L）的步骤 —— 规则 6 的夹具。 */
    private static StepNode withResource(StepNode base, BigDecimal cpu, Long memory) {
        return new StepNode(base.stepName(), base.stepType(), base.operatorId(), base.operatorVersionId(),
                base.params(), base.customParams(), 7L, cpu, null, memory, null, base.retryCount());
    }

    private static EdgeLink edge(int source, int target) {
        return new EdgeLink(source, target);
    }

    /** 已发布 + 必填 input_path + 声明了 file_path / row_count 两个输出的算子规格。 */
    private static OperatorSpec published() {
        return new OperatorSpec("PUBLISHED", Set.of("input_path"), Set.of("file_path", "row_count"));
    }

    private static DagValidationContext ctx(List<StepNode> steps, List<EdgeLink> edges, Concurrency concurrency,
                                            Map<Long, OperatorSpec> specs, Map<Long, ClusterSpec> clusters) {
        return new DagValidationContext(steps, edges, concurrency, specs, clusters);
    }

    /** 发布态的标准夹具（并发已声明、算子已发布）。 */
    private static DagValidationContext publishCtx(List<StepNode> steps, List<EdgeLink> edges) {
        return new DagValidationContext(new ArrayList<>(steps), edges, new Concurrency("FORBID", 1),
                new HashMap<>(Map.of(11L, published())), new LinkedHashMap<>());
    }
}
