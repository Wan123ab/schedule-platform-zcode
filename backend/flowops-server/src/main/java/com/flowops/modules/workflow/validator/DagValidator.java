package com.flowops.modules.workflow.validator;

import com.flowops.modules.workflow.validator.DagValidationContext.Concurrency;
import com.flowops.modules.workflow.validator.DagValidationContext.EdgeLink;
import com.flowops.modules.workflow.validator.DagValidationContext.OperatorSpec;
import com.flowops.modules.workflow.validator.DagValidationContext.StepNode;
import com.flowops.modules.workflow.validator.VariableRefParser.VariableRef;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 校验器（docs/07 §9.2：PRD §10.8 的 8 条 + 实现补充 2 条，共 10 条）。
 *
 * <p><b>两个时机，两套规则</b>（docs/07 §9.2 的"校验时机"，这一条很容易做错）：</p>
 * <ul>
 *   <li>{@link Phase#STRUCTURAL}（{@code PUT /workflow-versions/{id}} 保存草稿）：
 *       只做规则 <b>1 / 5 / 10</b>。保存草稿<b>不能</b>全量校验 —— 否则
 *       "画了半个图想先存一下"会被拒，编辑中途无法保存，体验不可接受；</li>
 *   <li>{@link Phase#PUBLISH}（{@code POST /workflows/{id}/publish}）：全量 10 条。
 *       因为发布后版本冻结、任务会长期绑定它，此刻放过的错会变成线上事故。</li>
 * </ul>
 *
 * <p><b>一次收齐、不抛第一个错</b>：返回 {@code List<DagViolation>} 而不是抛异常。
 * 用户面对的是画布，逐条改、逐条被打回是最糟的交互（与 42210 同口径）。</p>
 *
 * <p><b>规则顺序 = 输出顺序</b>：固定按 1→10 收集，错误列表顺序稳定，
 * 前端与测试都能依赖它，而不必靠"碰巧的顺序"。</p>
 *
 * <p><b>纯函数</b>：不注入任何 Mapper，全部输入来自 {@link DagValidationContext}。
 * 这样"逐条规则造错误用例"才是真单测（见 {@code DagValidatorTest}）。</p>
 */
public final class DagValidator {

    /** 规则 9 的重试上限（E-04：防止重试风暴打垮节点）。 */
    public static final int MAX_RETRY = 10;

    /** 并发策略白名单（docs/05 §3.4 的 DDL CHECK）。 */
    private static final Set<String> CONCURRENCY_POLICIES = Set.of("FORBID", "ALLOW", "QUEUE");

    /** 校验时机：决定跑结构子集还是全量。 */
    public enum Phase {
        /** 保存草稿：规则 1 / 5 / 10。 */
        STRUCTURAL,
        /** 发布：全量 10 条。 */
        PUBLISH
    }

    /**
     * 校验。
     *
     * @return 违规列表；全部通过时为空列表（调用方据 {@code isEmpty()} 决定是否放行）
     */
    public List<DagViolation> validate(DagValidationContext ctx, Phase phase) {
        List<DagViolation> out = new ArrayList<>();
        List<StepNode> steps = ctx.steps() == null ? List.of() : ctx.steps();
        List<EdgeLink> edges = ctx.edges() == null ? List.of() : ctx.edges();

        int n = steps.size();
        List<List<Integer>> outAdj = new ArrayList<>(n);
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++) {
            outAdj.add(new ArrayList<>());
        }
        for (EdgeLink e : edges) {
            if (e.sourceIndex() < 0 || e.sourceIndex() >= n || e.targetIndex() < 0 || e.targetIndex() >= n) {
                // 上下文装配阶段的越界边：跳过而不是抛异常 —— 宁可少判一条，
                // 也不要在"用户点保存"的路径上抛一个与他输入无关的数组越界
                continue;
            }
            outAdj.get(e.sourceIndex()).add(e.targetIndex());
            inDegree[e.targetIndex()]++;
        }

        // ── 规则 1：至少一个入度 0 步骤，且所有执行步骤可达 ──
        checkReachability(steps, outAdj, inDegree, out);
        // ── 规则 5：无环 ──
        checkAcyclic(steps, outAdj, inDegree, out);
        // ── 规则 10：步骤名唯一 ──
        checkStepNameUnique(steps, out);

        if (phase == Phase.PUBLISH) {
            // ── 规则 2：TASK 必须绑定算子 + 算子版本 ──
            checkOperatorBound(steps, out);
            // ── 规则 7：引用的算子版本必须已发布（42218）──
            Map<Integer, OperatorSpec> specs = checkOperatorPublished(steps, ctx, out);
            // ── 规则 3：必填参数必须填写 ──
            checkRequiredParams(steps, specs, out);
            // ── 规则 4：变量引用有效且为可达上游（42214）──
            checkVariableRefs(steps, outAdj, specs, out);
            // ── 规则 6：目标集群资源不超上限 ──
            checkResources(steps, ctx, out);
            // ── 规则 8：必须声明并发控制配置 ──
            checkConcurrency(ctx.concurrency(), out);
            // ── 规则 9：重试次数 ≤ MAX_RETRY ──
            checkRetry(steps, out);
        }
        return out;
    }

    // ── 规则 1 ──────────────────────────────────────────────

    private void checkReachability(List<StepNode> steps, List<List<Integer>> outAdj, int[] inDegree,
                                   List<DagViolation> out) {
        int n = steps.size();
        if (n == 0) {
            // 空图：保存草稿阶段的常态（刚新建的版本还没画任何节点）。
            // ⚠️ docs/07 §9.2 的 10 条规则**都不覆盖**"工作流一个步骤都没有"这一情形
            // —— v3 评审明确删掉了自创的"至少 1 个步骤"（见 §9.2 开头的修订说明，
            // 规则编号以 PRD §10.8 为准）。故此处不报错，登记为 README-M3 的已知缺口。
            return;
        }
        Set<Integer> sources = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                sources.add(i);
            }
        }
        if (sources.isEmpty()) {
            out.add(DagViolation.of("1", null,
                    "流程图没有任何入口步骤（每个步骤都有上游），无法确定从哪里开始执行"));
            return;
        }
        Set<Integer> reachable = reachableFrom(sources, outAdj);
        for (int i = 0; i < n; i++) {
            if (!reachable.contains(i) && !steps.get(i).isNote()) {
                out.add(DagViolation.of("1", steps.get(i).stepName(),
                        "步骤「" + steps.get(i).stepName() + "」不可达"));
            }
        }
    }

    private Set<Integer> reachableFrom(Set<Integer> from, List<List<Integer>> outAdj) {
        Set<Integer> seen = new HashSet<>(from);
        Deque<Integer> queue = new ArrayDeque<>(from);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int next : outAdj.get(cur)) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    // ── 规则 5 ──────────────────────────────────────────────

    private void checkAcyclic(List<StepNode> steps, List<List<Integer>> outAdj, int[] inDegree,
                              List<DagViolation> out) {
        int n = steps.size();
        int[] deg = inDegree.clone();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) {
                queue.add(i);
            }
        }
        int processed = 0;
        Set<Integer> done = new HashSet<>();
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            done.add(cur);
            processed++;
            for (int next : outAdj.get(cur)) {
                if (--deg[next] == 0) {
                    queue.add(next);
                }
            }
        }
        if (processed == n) {
            return;
        }
        // 剩下的节点就是环成员（或被环阻塞的下游）；从环里走一圈把路径打出来，
        // 用户能直接照着"哪个连哪个"去删线，而不是拿到一句"存在环"
        out.add(DagViolation.of("5", null, "检测到循环依赖：" + renderCycle(steps, outAdj, done)));
    }

    /** 从任一未处理节点出发，沿"仍未处理"的边走，走到重复访问即得一段环路径。 */
    private String renderCycle(List<StepNode> steps, List<List<Integer>> outAdj, Set<Integer> done) {
        int start = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (!done.contains(i)) {
                start = i;
                break;
            }
        }
        List<Integer> path = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int cur = start;
        while (cur >= 0 && visited.add(cur)) {
            path.add(cur);
            int next = -1;
            for (int candidate : outAdj.get(cur)) {
                if (!done.contains(candidate)) {
                    next = candidate;
                    break;
                }
            }
            cur = next;
        }
        StringBuilder sb = new StringBuilder();
        for (int idx : path) {
            sb.append(steps.get(idx).stepName()).append(" → ");
        }
        // 闭环收尾：如果走到某个已访问过的节点，说明回到了环上，补上它的名字
        if (cur >= 0 && cur == path.get(0)) {
            sb.append(steps.get(cur).stepName());
        } else {
            sb.append(steps.get(start).stepName());
        }
        return sb.toString();
    }

    // ── 规则 10 ─────────────────────────────────────────────

    private void checkStepNameUnique(List<StepNode> steps, List<DagViolation> out) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (StepNode s : steps) {
            seen.merge(s.stepName(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() > 1) {
                out.add(DagViolation.of("10", e.getKey(), "步骤名「" + e.getKey() + "」重复"));
            }
        }
    }

    // ── 规则 2 ──────────────────────────────────────────────

    private void checkOperatorBound(List<StepNode> steps, List<DagViolation> out) {
        for (StepNode s : steps) {
            if (s.isNote()) {
                continue;
            }
            if (s.operatorId() == null || s.operatorVersionId() == null) {
                out.add(DagViolation.of("2", s.stepName(),
                        "步骤「" + s.stepName() + "」未选择算子或算子版本"));
            }
        }
    }

    // ── 规则 7 ──────────────────────────────────────────────

    /** @return 每个下标对应的算子规格（缺失/不可用的不放入），供规则 3 复用，避免重复查询装配 */
    private Map<Integer, OperatorSpec> checkOperatorPublished(List<StepNode> steps, DagValidationContext ctx,
                                                              List<DagViolation> out) {
        Map<Integer, OperatorSpec> specs = new HashMap<>();
        Map<Long, OperatorSpec> available = ctx.operatorSpecs() == null ? Map.of() : ctx.operatorSpecs();
        for (int i = 0; i < steps.size(); i++) {
            StepNode s = steps.get(i);
            if (s.isNote() || s.operatorVersionId() == null) {
                continue;
            }
            OperatorSpec spec = available.get(s.operatorVersionId());
            if (spec == null) {
                out.add(DagViolation.unpublishedOperator(s.stepName(),
                        "步骤「" + s.stepName() + "」引用的算子版本不存在或已被删除"));
                continue;
            }
            if (!"PUBLISHED".equals(spec.publishStatus())) {
                out.add(DagViolation.unpublishedOperator(s.stepName(),
                        "步骤「" + s.stepName() + "」引用了未发布的算子版本"));
                continue;
            }
            specs.put(i, spec);
        }
        return specs;
    }

    // ── 规则 3 ──────────────────────────────────────────────

    private void checkRequiredParams(List<StepNode> steps, Map<Integer, OperatorSpec> specs,
                                     List<DagViolation> out) {
        for (Map.Entry<Integer, OperatorSpec> e : specs.entrySet()) {
            StepNode s = steps.get(e.getKey());
            Set<String> required = e.getValue().requiredParams() == null ? Set.of() : e.getValue().requiredParams();
            Map<String, Object> params = s.params() == null ? Map.of() : s.params();
            for (String key : required) {
                Object value = params.get(key);
                boolean blank = value == null || (value instanceof String str && str.isBlank());
                if (blank) {
                    out.add(DagViolation.of("3", s.stepName(),
                            "步骤「" + s.stepName() + "」缺少必填参数「" + key + "」"));
                }
            }
        }
    }

    // ── 规则 4 ──────────────────────────────────────────────

    private void checkVariableRefs(List<StepNode> steps, List<List<Integer>> outAdj,
                                   Map<Integer, OperatorSpec> specs, List<DagViolation> out) {
        Map<String, Integer> indexByName = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            // 重名已由规则 10 报出；这里取首个出现的下标，避免同一条错报两遍
            indexByName.putIfAbsent(steps.get(i).stepName(), i);
        }
        for (int i = 0; i < steps.size(); i++) {
            StepNode s = steps.get(i);
            if (s.isNote()) {
                continue;
            }
            List<VariableRef> refs = new ArrayList<>();
            refs.addAll(VariableRefParser.parseParams(s.params()));
            refs.addAll(VariableRefParser.parseParams(s.customParams()));
            if (refs.isEmpty()) {
                continue;
            }
            Set<Integer> ancestors = ancestorsOf(i, outAdj);
            for (VariableRef ref : refs) {
                if (!ref.valid()) {
                    out.add(DagViolation.variable("4", s.stepName(),
                            "步骤「" + s.stepName() + "」的变量引用有误：" + ref.error() + "（" + ref.raw() + "）"));
                    continue;
                }
                if (!ref.isStepOutput()) {
                    // trigger / project / platform / task 类引用不由 DAG 节点提供，一期不判定可达性
                    continue;
                }
                Integer sourceIndex = indexByName.get(ref.stepName());
                if (sourceIndex == null) {
                    out.add(DagViolation.variable("4", s.stepName(),
                            "步骤「" + s.stepName() + "」引用了不存在的步骤「" + ref.stepName() + "」（" + ref.raw() + "）"));
                    continue;
                }
                if (sourceIndex == i) {
                    out.add(DagViolation.variable("4", s.stepName(),
                            "步骤「" + s.stepName() + "」不能引用自身的输出（" + ref.raw() + "）"));
                    continue;
                }
                if (!ancestors.contains(sourceIndex)) {
                    out.add(DagViolation.variable("4", s.stepName(),
                            "步骤「" + s.stepName() + "」引用了不可达的变量 " + ref.raw()));
                    continue;
                }
                OperatorSpec sourceSpec = specs.get(sourceIndex);
                Set<String> declared = sourceSpec == null || sourceSpec.declaredOutputs() == null
                        ? Set.of() : sourceSpec.declaredOutputs();
                if (!declared.isEmpty() && !declared.contains(ref.varName())) {
                    out.add(DagViolation.variable("4", s.stepName(),
                            "步骤「" + s.stepName() + "」引用了步骤「" + ref.stepName()
                                    + "」未声明的输出「" + ref.varName() + "」"));
                }
            }
        }
    }

    /** 反向可达集：{@code i} 的全部（传递）上游。 */
    private Set<Integer> ancestorsOf(int i, List<List<Integer>> outAdj) {
        Set<Integer> result = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(i);
        Set<Integer> seen = new HashSet<>();
        seen.add(i);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int src = 0; src < outAdj.size(); src++) {
                if (outAdj.get(src).contains(cur) && seen.add(src)) {
                    result.add(src);
                    queue.add(src);
                }
            }
        }
        return result;
    }

    // ── 规则 6 ──────────────────────────────────────────────

    private void checkResources(List<StepNode> steps, DagValidationContext ctx, List<DagViolation> out) {
        Map<Long, DagValidationContext.ClusterSpec> clusterSpecs =
                ctx.clusterSpecs() == null ? Map.of() : ctx.clusterSpecs();
        if (clusterSpecs.isEmpty()) {
            return;
        }
        // 按目标集群聚合：同一集群上要跑的步骤申请量之和不能超过集群总量
        Map<Long, List<StepNode>> byCluster = new LinkedHashMap<>();
        for (StepNode s : steps) {
            if (s.isNote() || s.targetClusterId() == null) {
                continue;
            }
            byCluster.computeIfAbsent(s.targetClusterId(), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<Long, List<StepNode>> e : byCluster.entrySet()) {
            DagValidationContext.ClusterSpec spec = clusterSpecs.get(e.getKey());
            if (spec == null || spec.limit() == null) {
                continue;
            }
            List<StepNode> group = e.getValue();
            String clusterRef = spec.clusterName() == null
                    ? spec.clusterId() : spec.clusterName() + "（" + spec.clusterId() + "）";
            StepNode heaviest = group.stream()
                    .max((a, b) -> compareCpu(a.cpu(), b.cpu()))
                    .orElse(group.get(0));
            String prefix = "步骤「" + heaviest.stepName() + "」等 " + group.size() + " 个步骤在目标集群 "
                    + clusterRef + " 上";
            overLimit(prefix, "cpu", sumCpu(group), spec.limit().cpu(), heaviest, out);
            overLimit(prefix, "gpu", sumGpu(group), spec.limit().gpu(), heaviest, out);
            overLimit(prefix, "内存(MB)", sumLong(group, true), spec.limit().memory(), heaviest, out);
            overLimit(prefix, "磁盘(MB)", sumLong(group, false), spec.limit().disk(), heaviest, out);
        }
    }

    private void overLimit(String prefix, String dimension, double used, Object limit, StepNode anchor,
                           List<DagViolation> out) {
        if (limit == null || used <= 0) {
            return;
        }
        double cap = limit instanceof Long l ? l.doubleValue() : ((Number) limit).doubleValue();
        if (used > cap) {
            out.add(DagViolation.of("6", anchor.stepName(),
                    prefix + "的 " + dimension + " 合计申请 " + trim(used) + " / 上限 " + trim(cap)));
        }
    }

    private int compareCpu(BigDecimal a, BigDecimal b) {
        BigDecimal left = a == null ? BigDecimal.ZERO : a;
        BigDecimal right = b == null ? BigDecimal.ZERO : b;
        return left.compareTo(right);
    }

    private double sumCpu(List<StepNode> group) {
        return group.stream().mapToDouble(s -> s.cpu() == null ? 0 : s.cpu().doubleValue()).sum();
    }

    private double sumGpu(List<StepNode> group) {
        return group.stream().mapToDouble(s -> s.gpu() == null ? 0 : s.gpu().doubleValue()).sum();
    }

    private double sumLong(List<StepNode> group, boolean memory) {
        return group.stream().mapToDouble(s -> {
            Long v = memory ? s.memory() : s.disk();
            return v == null ? 0 : v.doubleValue();
        }).sum();
    }

    /** 去掉 {@code 8.00} 这类无意义小数，让提示读起来像人写的。 */
    private String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ── 规则 8 ──────────────────────────────────────────────

    private void checkConcurrency(Concurrency concurrency, List<DagViolation> out) {
        String policy = concurrency == null ? null : concurrency.policy();
        Integer maxParallel = concurrency == null ? null : concurrency.maxParallelRuns();
        if (policy == null || policy.isBlank() || !CONCURRENCY_POLICIES.contains(policy)) {
            out.add(DagViolation.of("8", null, "工作流未声明并发控制配置"));
            return;
        }
        if (maxParallel == null || maxParallel < 1) {
            out.add(DagViolation.of("8", null, "工作流的最大并行数必须大于等于 1"));
        }
    }

    // ── 规则 9 ──────────────────────────────────────────────

    private void checkRetry(List<StepNode> steps, List<DagViolation> out) {
        for (StepNode s : steps) {
            if (s.retryCount() == null) {
                continue;
            }
            if (s.retryCount() > MAX_RETRY) {
                out.add(DagViolation.of("9", s.stepName(),
                        "步骤「" + s.stepName() + "」重试次数超过上限 " + MAX_RETRY));
            } else if (s.retryCount() < 0) {
                out.add(DagViolation.of("9", s.stepName(),
                        "步骤「" + s.stepName() + "」重试次数不能为负数"));
            }
        }
    }
}
