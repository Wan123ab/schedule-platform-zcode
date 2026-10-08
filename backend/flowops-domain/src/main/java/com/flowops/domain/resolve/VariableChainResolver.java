package com.flowops.domain.resolve;

import com.flowops.common.util.OrderedCollections;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 六层变量覆盖链解析器（docs/03 §4.4 / docs/07 §9.3，PRD §10.0.1）。
 *
 * <p><b>优先级（低 → 高，后者覆盖前者）</b>：</p>
 * <pre>
 * 平台变量 &lt; 项目参数 &lt; 工作流参数 &lt; 触发时参数 &lt; 上游步骤输出 &lt; 步骤参数
 * </pre>
 *
 * <p><b>两类渲染目标，一张上下文</b>（这是 docs 两处描述的汇合点）：</p>
 * <ul>
 *   <li><b>步骤参数</b>（第 6 层的值本身）可含引用（D-20 语法），对着 1~5 层的扁平
 *       上下文解析 —— 不允许引用第 6 层自己（自己引用自己只会产生循环）。</li>
 *   <li><b>启动命令</b>（{@code start_command}）里的引用对着 1~6 层合并后的扁平上下文
 *       解析 —— 这正是 docs/03 §4.4 的 {@code ctx.putAll(...)} 设计：步骤参数最后
 *       put，所以同名时<b>步骤参数赢</b>。</li>
 * </ul>
 *
 * <p><b>带前缀引用 vs 裸引用</b>（docs/07 §9.1 与 docs/03 §4.4 的写法矛盾，按"都支持"
 * 落定）：{@code ${project.param.x}} 这类<b>点名引用</b>只看对应层，不受覆盖链影响 ——
 * "点名"与"覆盖"是两种意图，混在一起覆盖链就失去意义；{@code ${taskId}} 这类
 * <b>裸引用</b>从扁平上下文取值，由覆盖链决定哪层赢，溯源记录实际胜出的层。</p>
 *
 * <p><b>排障溯源（PRD §10.0.4）</b>：每个参数记录它最终由哪一层产出的
 * {@code sources}，任务详情页据此展示"这个值是哪一层给的"。</p>
 *
 * <p><b>敏感值脱敏（M-07）</b>：调用方传入敏感参数名集合。本类返回<b>两份</b>值 ——
 * {@code resolvedParams} 是真实值（给执行用），{@code snapshotParams} 是脱敏后的
 * 快照值（给 {@code task.variable_snapshot} 落库与展示用），敏感项只写 {@code "***"}
 * 加 {@code masked=true} 的溯源，<b>不落原文</b>。</p>
 *
 * <p><b>为什么放 flowops-domain</b>：server（算子试运行、任务详情）与
 * scheduler（步骤下发前解析）是两条独立链路，domain 是二者唯一的公共依赖；
 * 纯函数、无 Mapper，两边都能直接单测。</p>
 */
public final class VariableChainResolver {

    /** 覆盖链的六层，声明顺序即优先级（ordinal 小 = 优先级低）。 */
    public enum Layer {
        PLATFORM("平台变量"),
        PROJECT("项目参数"),
        WORKFLOW("工作流参数"),
        TRIGGER("触发时参数"),
        UPSTREAM_OUTPUT("上游步骤输出"),
        STEP_PARAM("步骤参数");

        private final String label;

        Layer(String label) {
            this.label = label;
        }

        /** 中文名，用于错误消息与快照展示（不直接用枚举名，用户看不懂 UPSTREAM_OUTPUT）。 */
        public String label() {
            return label;
        }
    }

    /** 六层的输入。允许任何一层为 {@code null}（没有该层的数据是常态）。 */
    public record Chain(
            Map<String, Object> platformVars,
            Map<String, Object> projectParams,
            Map<String, Object> workflowParams,
            Map<String, Object> triggerParams,
            /** 步骤名 → 该步骤的输出变量集。只收<b>已完成上游</b>的输出，由调用方装配。 */
            Map<String, Map<String, Object>> upstreamOutputs) {

        public static Chain empty() {
            return new Chain(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        Map<String, Object> layerMap(Layer layer) {
            return switch (layer) {
                case PLATFORM -> platformVars;
                case PROJECT -> projectParams;
                case WORKFLOW -> workflowParams;
                case TRIGGER -> triggerParams;
                default -> null; // UPSTREAM_OUTPUT 是二维结构；STEP_PARAM 是被解析对象，不是数据源
            };
        }
    }

    /** 一个参数的来源记录（PRD §10.0.4 的 sources 条目）。 */
    public record Source(Layer layer, String ref, boolean masked) {
    }

    /**
     * 解析结果。
     *
     * @param resolvedParams 参数名 → 解析后的<b>真实值</b>（给执行用）
     * @param snapshotParams 参数名 → 快照值（敏感项已脱敏为 "***"，给落库/展示用）
     * @param sources        参数名 → 来源
     * @param errors         解析失败清单（非空 = 有引用落空；调用方据此判步骤失败，PRD §10.0.2）
     */
    public record Result(
            Map<String, Object> resolvedParams,
            Map<String, Object> snapshotParams,
            Map<String, Source> sources,
            List<String> errors) {

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** 命令渲染结果。 */
    public record Rendered(String command, List<String> errors) {
    }

    /** 快照里敏感值的占位符（M-07：不落原文）。 */
    public static final String MASKED_VALUE = "***";

    private VariableChainResolver() {
    }

    /**
     * 解析一个步骤的参数（第 6 层）。
     *
     * @param chain         1~5 层数据
     * @param stepParams    步骤参数（值可含 D-20 引用）
     * @param sensitiveKeys 敏感参数名集合（参数模板的 {@code sensitive} 标记）
     */
    public static Result resolve(Chain chain, Map<String, Object> stepParams, Set<String> sensitiveKeys) {
        // 扁平上下文只含 1~5 层：步骤参数不允许引用自己这一层（自引用只会产生循环）
        Flat flat = flatten(chain, null);
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Source> sources = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> sensitive = sensitiveKeys == null ? Set.of() : sensitiveKeys;

        if (stepParams != null) {
            for (Map.Entry<String, Object> e : stepParams.entrySet()) {
                String key = e.getKey();
                Object raw = e.getValue();
                Resolution r = resolveValue(chain, flat, key, raw);
                errors.addAll(r.errors);
                resolved.put(key, r.value);
                boolean masked = sensitive.contains(key);
                snapshot.put(key, masked ? MASKED_VALUE : r.value);
                sources.put(key, new Source(r.layer, r.ref, masked));
            }
        }
        // 三张 map 都用保序副本而不是 Map.copyOf：snapshotParams 要落 task.variable_snapshot
        // 并回显给参数面板、Sources 要拼"这个值来自哪一层"的溯源展示 —— 都是"会被遍历"的
        // 结果，不能接受 JDK 不可变容器那个每次 JVM 启动都变的随机迭代顺序（见 OrderedCollections）
        return new Result(OrderedCollections.orderedMap(resolved), OrderedCollections.orderedMap(snapshot),
                OrderedCollections.orderedMap(sources), List.copyOf(errors));
    }

    /**
     * 渲染启动命令：引用对着 1~6 层合并的扁平上下文取值。
     *
     * <p>合并顺序就是覆盖链：底层先放、高层后放（LinkedHashMap 后者覆盖前者），
     * 步骤参数（第 6 层，用解析后的真实值）最后放 —— 这一行代码就是"步骤参数最高"
     * 的全部实现。</p>
     *
     * @return 渲染后的命令；命令为 null 原样返回 null（校验层负责要求它非空）
     */
    public static Rendered renderCommand(Chain chain, Map<String, Object> resolvedParams, String command) {
        if (command == null || command.isEmpty()) {
            return new Rendered(command, List.of());
        }
        Flat flat = flatten(chain, resolvedParams);
        List<String> errors = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (VariableRefParser.VariableRef ref : VariableRefParser.parse(command)) {
            int at = command.indexOf(ref.raw(), last);
            if (at < 0) { // 理论不可能（parse 就是从 command 里找的），防御一下
                continue;
            }
            sb.append(command, last, at);
            if (!ref.valid()) {
                errors.add("启动命令里的引用格式非法：" + ref.raw() + "（" + ref.error() + "）");
                sb.append(ref.raw()); // 原样保留，让失败原因在日志里肉眼可见
            } else {
                Lookup hit = lookup(chain, flat, ref);
                if (hit == null) {
                    errors.add("启动命令引用了上下文里不存在的变量：" + ref.raw());
                    sb.append(ref.raw());
                } else {
                    sb.append(String.valueOf(hit.value()));
                }
            }
            last = at + ref.raw().length();
        }
        sb.append(command.substring(last));
        return new Rendered(sb.toString(), List.copyOf(errors));
    }

    // ── 内部：单个值的解析 ─────────────────────────────────

    private record Resolution(Object value, Layer layer, String ref, List<String> errors) {
    }

    private record Lookup(Object value) {
    }

    /** 扁平上下文 + 每个键的溯源层（裸引用要回答"这个值是哪层给的"）。 */
    private record Flat(Map<String, Object> values, Map<String, Layer> layers) {
    }

    private static Resolution resolveValue(Chain chain, Flat flat, String key, Object raw) {
        if (!(raw instanceof String s) || s.isEmpty()) {
            // 非字符串（数字/布尔/嵌套结构）不含引用，原样透传；来源记为 STEP_PARAM（值本身就在这一层）
            return new Resolution(raw, Layer.STEP_PARAM, null, List.of());
        }
        List<VariableRefParser.VariableRef> refs = VariableRefParser.parse(s);
        if (refs.isEmpty()) {
            return new Resolution(raw, Layer.STEP_PARAM, null, List.of());
        }
        // 整串恰好就是一个引用 → 透传原对象（上游输出可能是数字/布尔，不该被 String.valueOf 拍平）
        if (refs.size() == 1 && refs.get(0).raw().equals(s)) {
            VariableRefParser.VariableRef ref = refs.get(0);
            if (!ref.valid()) {
                return new Resolution(s, Layer.STEP_PARAM, ref.raw(),
                        List.of("步骤参数「" + key + "」的引用格式非法：" + ref.raw() + "（" + ref.error() + "）"));
            }
            Lookup hit = lookup(chain, flat, ref);
            if (hit == null) {
                return new Resolution(s, refLayer(ref, flat), ref.raw(),
                        List.of("步骤参数「" + key + "」引用的变量不存在或上游未产出：" + ref.raw()));
            }
            return new Resolution(hit.value(), refLayer(ref, flat), ref.raw(), List.of());
        }
        // 混排（"前缀${ref}后缀"）→ 字符串拼接
        List<String> errors = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int last = 0;
        String firstRef = null;
        for (VariableRefParser.VariableRef ref : refs) {
            int at = s.indexOf(ref.raw(), last);
            if (at < 0) {
                continue;
            }
            sb.append(s, last, at);
            if (firstRef == null) {
                firstRef = ref.raw();
            }
            if (!ref.valid()) {
                errors.add("步骤参数「" + key + "」的引用格式非法：" + ref.raw() + "（" + ref.error() + "）");
                sb.append(ref.raw());
            } else {
                Lookup hit = lookup(chain, flat, ref);
                if (hit == null) {
                    errors.add("步骤参数「" + key + "」引用的变量不存在或上游未产出：" + ref.raw());
                    sb.append(ref.raw());
                } else {
                    sb.append(String.valueOf(hit.value()));
                }
            }
            last = at + ref.raw().length();
        }
        sb.append(s.substring(last));
        return new Resolution(sb.toString(), Layer.STEP_PARAM, firstRef, errors);
    }

    /**
     * 按引用取值。
     *
     * @param flat 扁平上下文（裸引用与命令渲染用）
     */
    private static Lookup lookup(Chain chain, Flat flat, VariableRefParser.VariableRef ref) {
        if (!ref.valid()) {
            return null;
        }
        if (ref.isBare()) {
            if (flat == null || !flat.values().containsKey(ref.varName())) {
                return null;
            }
            return new Lookup(flat.values().get(ref.varName()));
        }
        if (ref.isStepOutput()) {
            Map<String, Object> outputs = chain.upstreamOutputs() == null
                    ? null : chain.upstreamOutputs().get(ref.stepName());
            if (outputs == null || !outputs.containsKey(ref.varName())) {
                return null;
            }
            return new Lookup(outputs.get(ref.varName()));
        }
        // 点名非步骤层：只看对应层的 map，不受覆盖链影响。
        // 键 = 来源前缀后的整段路径（${project.param.biz_date} → "param.biz_date"）；
        // docs 两处示例风格不同（${project.dataRoot} 无中段 / ${project.param.biz_date} 有
        // "param." 中段），因此整段找不到时再退化试一次"去掉第一段"。
        Map<String, Object> layerMap = chain.layerMap(refLayer(ref, null));
        if (layerMap == null) {
            return null;
        }
        String key = pathAfterSource(ref);
        if (layerMap.containsKey(key)) {
            return new Lookup(layerMap.get(key));
        }
        int dot = key.indexOf('.');
        if (dot > 0 && layerMap.containsKey(key.substring(dot + 1))) {
            return new Lookup(layerMap.get(key.substring(dot + 1)));
        }
        return null;
    }

    /** 带前缀引用 → 它语义上属于哪一层（用于溯源）。裸引用需要 flat 的溯源表。 */
    private static Layer refLayer(VariableRefParser.VariableRef ref, Flat flat) {
        if (ref.isBare()) {
            if (flat != null) {
                Layer layer = flat.layers().get(ref.varName());
                if (layer != null) {
                    return layer;
                }
            }
            return Layer.STEP_PARAM;
        }
        return switch (ref.source()) {
            case "step" -> Layer.UPSTREAM_OUTPUT;
            case "trigger" -> Layer.TRIGGER;
            case "project" -> Layer.PROJECT;
            case "platform" -> Layer.PLATFORM;
            // task 级系统量与平台变量同源（都是运行环境注入），溯源归 PLATFORM
            case "task" -> Layer.PLATFORM;
            default -> Layer.STEP_PARAM;
        };
    }

    /** 非步骤前缀引用在层内 map 里的键：来源前缀之后的整段（不含 ${} 与前缀）。 */
    private static String pathAfterSource(VariableRefParser.VariableRef ref) {
        String body = ref.raw().substring(2, ref.raw().length() - 1).trim();
        int dot = body.indexOf('.');
        return body.substring(dot + 1).trim();
    }

    /** 六层扁平合并（docs/03 §4.4 的 ctx 设计）。{@code stepParams} 为 null 时只合并 1~5 层。 */
    private static Flat flatten(Chain chain, Map<String, Object> stepParams) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Layer> layers = new LinkedHashMap<>();
        for (Layer layer : List.of(Layer.PLATFORM, Layer.PROJECT, Layer.WORKFLOW, Layer.TRIGGER)) {
            Map<String, Object> m = chain.layerMap(layer);
            if (m != null) {
                m.forEach((k, v) -> {
                    values.put(k, v);
                    layers.put(k, layer);
                });
            }
        }
        if (chain.upstreamOutputs() != null) {
            for (Map<String, Object> outputs : chain.upstreamOutputs().values()) {
                if (outputs != null) {
                    outputs.forEach((k, v) -> {
                        values.put(k, v);
                        layers.put(k, Layer.UPSTREAM_OUTPUT);
                    });
                }
            }
        }
        if (stepParams != null) {
            stepParams.forEach((k, v) -> {
                values.put(k, v);
                layers.put(k, Layer.STEP_PARAM);
            });
        }
        // 只有 get 查询，但 Flat 是会被上层拿去做"合并顺序"排查的对象，
        // 保序副本让"哪一层先放"这件事在调试与日志里读得出来
        return new Flat(OrderedCollections.orderedMap(values), OrderedCollections.orderedMap(layers));
    }

    /** 供测试与调用方枚举：链的层次名（按优先级低 → 高）。 */
    public static List<String> layerLabels() {
        return List.of(Layer.PLATFORM.label(), Layer.PROJECT.label(), Layer.WORKFLOW.label(),
                Layer.TRIGGER.label(), Layer.UPSTREAM_OUTPUT.label(), Layer.STEP_PARAM.label());
    }
}
