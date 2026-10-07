package com.flowops.domain.resolve;

import com.flowops.domain.resolve.VariableChainResolver.Chain;
import com.flowops.domain.resolve.VariableChainResolver.Layer;
import com.flowops.domain.resolve.VariableChainResolver.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六层覆盖链解析器单测。
 *
 * <p>docs/03 §4.4 的原话："逐层覆盖优先级必须有可读的测试名，否则'哪层赢'永远说不清"。
 * 因此每条覆盖规则的用例名直接写成「谁覆盖谁」。</p>
 */
class VariableChainResolverTest {

    /** 五层都放同名键 bizDate，逐层改值 —— 断言最终值 + 溯源层。 */
    private static final Map<String, Object> PLATFORM = Map.of("bizDate", "P");
    private static final Map<String, Object> PROJECT = Map.of("bizDate", "J");
    private static final Map<String, Object> WORKFLOW = Map.of("bizDate", "W");
    private static final Map<String, Object> TRIGGER = Map.of("bizDate", "T");

    /** upstream：步骤名 → 输出变量集；trigger：触发时参数。其余三层固定，便于聚焦断言。 */
    @SuppressWarnings("unchecked")
    private static Chain chain(Map<String, Map<String, Object>> upstream, Map<String, Object> trigger) {
        return new Chain(PLATFORM, PROJECT, WORKFLOW, trigger, upstream);
    }

    // ── 覆盖链优先级（每条一个用例，名字即答案） ──────────────

    @Test
    void 项目参数覆盖平台变量() {
        Chain c = new Chain(PLATFORM, PROJECT, null, null, null);
        Result r = VariableChainResolver.resolve(c, Map.of("p", "${bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "J");
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.PROJECT);
    }

    @Test
    void 工作流参数覆盖项目参数() {
        Chain c = new Chain(PLATFORM, PROJECT, WORKFLOW, null, null);
        Result r = VariableChainResolver.resolve(c, Map.of("p", "${bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "W");
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.WORKFLOW);
    }

    @Test
    void 触发时参数覆盖工作流参数() {
        Result r = VariableChainResolver.resolve(chain(Map.of(), TRIGGER), Map.of("p", "${bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "T");
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.TRIGGER);
    }

    @Test
    void 上游步骤输出覆盖触发时参数() {
        Chain c = new Chain(PLATFORM, PROJECT, WORKFLOW, TRIGGER,
                Map.of("清洗", Map.of("bizDate", "U")));
        Result r = VariableChainResolver.resolve(c, Map.of("p", "${bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "U");
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.UPSTREAM_OUTPUT);
    }

    @Test
    void 步骤参数覆盖一切_用扁平命令渲染验证() {
        // 第 6 层"最高"的落点：start_command 里的裸引用，同名时步骤参数赢
        Chain c = chain(Map.of(), TRIGGER);
        Result params = VariableChainResolver.resolve(c, Map.of("bizDate", "STEP"), Set.of());
        VariableChainResolver.Rendered cmd =
                VariableChainResolver.renderCommand(c, params.resolvedParams(), "run --date ${bizDate}");
        assertThat(cmd.command()).isEqualTo("run --date STEP");
        assertThat(cmd.errors()).isEmpty();
    }

    @Test
    void 上游输出在命令渲染里也参与扁平上下文() {
        Chain c = new Chain(PLATFORM, PROJECT, WORKFLOW, TRIGGER, Map.of("清洗", Map.of("file", "/a/b")));
        VariableChainResolver.Rendered cmd =
                VariableChainResolver.renderCommand(c, Map.of(), "load ${file}");
        assertThat(cmd.command()).isEqualTo("load /a/b");
    }

    // ── 点名引用 vs 覆盖链 ───────────────────────────────────

    @Test
    void 点名引用不受覆盖链影响_点名项目就只看项目() {
        // ${project.bizDate} 只看项目参数，即使触发参数里有同名键
        Chain c = chain(Map.of(), TRIGGER);
        Result r = VariableChainResolver.resolve(c, Map.of("p", "${project.bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "J");
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.PROJECT);
    }

    @Test
    void 点名上游输出_键与值都按语法路由() {
        Chain c = chain(Map.of("数据清洗", Map.of("row_count", 15234)), Map.of());
        Result r = VariableChainResolver.resolve(c, Map.of("n", "${step.数据清洗.output.row_count}"), Set.of());
        // 整串就是一个引用 → 原类型透传（数字不拍平成字符串）
        assertThat(r.resolvedParams()).containsEntry("n", 15234);
        assertThat(r.sources().get("n").layer()).isEqualTo(Layer.UPSTREAM_OUTPUT);
        assertThat(r.sources().get("n").ref()).isEqualTo("${step.数据清洗.output.row_count}");
    }

    @Test
    void 点名触发参数() {
        Chain c = chain(Map.of(), Map.of("fire_time", "2026-10-08T00:00:00"));
        Result r = VariableChainResolver.resolve(c, Map.of("t", "${trigger.fire_time}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("t", "2026-10-08T00:00:00");
        assertThat(r.sources().get("t").layer()).isEqualTo(Layer.TRIGGER);
    }

    @Test
    void 点名平台变量_存在时取值() {
        Result r = VariableChainResolver.resolve(chain(Map.of(), Map.of()),
                Map.of("dir", "${platform.bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("dir", "P");
        assertThat(r.sources().get("dir").layer()).isEqualTo(Layer.PLATFORM);
    }

    @Test
    void 点名平台变量_不存在时报错且原值保留() {
        Result r = VariableChainResolver.resolve(chain(Map.of(), Map.of()),
                Map.of("dir", "${platform.base_dir}"), Set.of());
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.errors().get(0)).contains("base_dir").contains("不存在或上游未产出");
        assertThat(r.resolvedParams()).containsEntry("dir", "${platform.base_dir}");
    }

    @Test
    void 点名引用带param中段_退化匹配去掉中段的键() {
        // docs/07 §9.3 示例写 ${project.param.biz_date}，docs/03 §4.4 写 ${project.dataRoot}；
        // 两种风格都接：整段键找不到时退化试"去掉第一段"
        Result r = VariableChainResolver.resolve(chain(Map.of(), Map.of()),
                Map.of("d", "${project.param.bizDate}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("d", "J");
        assertThat(r.sources().get("d").layer()).isEqualTo(Layer.PROJECT);
    }

    // ── 失败路径 ─────────────────────────────────────────────

    @Test
    void 引用不存在的上游步骤_报错且原值保留() {
        Result r = VariableChainResolver.resolve(chain(Map.of(), Map.of()),
                Map.of("p", "${step.不存在的步骤.output.x}"), Set.of());
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.errors().get(0)).contains("不存在的步骤").contains("不存在或上游未产出");
        assertThat(r.resolvedParams()).containsEntry("p", "${step.不存在的步骤.output.x}");
    }

    @Test
    void 引用上游存在但未产出的变量_报错() {
        Chain c = chain(Map.of("清洗", Map.of("other", 1)), Map.of());
        Result r = VariableChainResolver.resolve(c, Map.of("p", "${step.清洗.output.row_count}"), Set.of());
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.errors().get(0)).contains("row_count");
    }

    @Test
    void 语法非法的引用_报格式错误而非变量不存在() {
        Result r = VariableChainResolver.resolve(Chain.empty(), Map.of("p", "${step.\"未闭合.output.x}"), Set.of());
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.errors().get(0)).contains("格式非法");
    }

    @Test
    void 未闭合引用_报错() {
        Result r = VariableChainResolver.resolve(Chain.empty(), Map.of("p", "前缀 ${step.A.output.x"), Set.of());
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.errors().get(0)).contains("未闭合");
    }

    // ── 拼接与类型 ───────────────────────────────────────────

    @Test
    void 混排引用_字符串拼接且数字被字符串化() {
        Chain c = chain(Map.of("清洗", Map.of("n", 42)), Map.of());
        Result r = VariableChainResolver.resolve(c, Map.of("p", "rows=${step.清洗.output.n}&v=1"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("p", "rows=42&v=1");
        // 混排的最终值是步骤参数层合成的字符串；ref 仍记录第一个引用以便排障
        assertThat(r.sources().get("p").layer()).isEqualTo(Layer.STEP_PARAM);
        assertThat(r.sources().get("p").ref()).isEqualTo("${step.清洗.output.n}");
    }

    @Test
    void 非字符串参数_原样透传不解析() {
        Map<String, Object> nested = Map.of("k", "v");
        Result r = VariableChainResolver.resolve(Chain.empty(), Map.of("n", 7, "b", true, "m", nested), Set.of());
        assertThat(r.resolvedParams()).containsEntry("n", 7).containsEntry("b", true).containsEntry("m", nested);
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void 部分引用失败_其余参数照常解析() {
        Chain c = chain(Map.of("清洗", Map.of("x", "1")), Map.of());
        Result r = VariableChainResolver.resolve(c,
                Map.of("ok", "${step.清洗.output.x}", "bad", "${step.清洗.output.missing}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("ok", "1");
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0)).contains("bad");
    }

    // ── 敏感值脱敏（M-07） ───────────────────────────────────

    @Test
    void 敏感参数_快照脱敏但真实值保留() {
        Result r = VariableChainResolver.resolve(chain(Map.of(), Map.of()),
                Map.of("api_key", "sk-123", "plain", "hello"), Set.of("api_key"));
        // 真实值给执行
        assertThat(r.resolvedParams()).containsEntry("api_key", "sk-123");
        // 快照不落原文
        assertThat(r.snapshotParams()).containsEntry("api_key", "***").containsEntry("plain", "hello");
        assertThat(r.sources().get("api_key").masked()).isTrue();
        assertThat(r.sources().get("plain").masked()).isFalse();
    }

    @Test
    void 敏感标记_即使引用了上游输出也脱敏() {
        Chain c = chain(Map.of("清洗", Map.of("token", "abc")), Map.of());
        Result r = VariableChainResolver.resolve(c, Map.of("t", "${step.清洗.output.token}"), Set.of("t"));
        assertThat(r.resolvedParams()).containsEntry("t", "abc");
        assertThat(r.snapshotParams()).containsEntry("t", "***");
    }

    // ── 命令渲染 ─────────────────────────────────────────────

    @Test
    void 命令渲染_语法非法引用原样保留并报错() {
        VariableChainResolver.Rendered r =
                VariableChainResolver.renderCommand(Chain.empty(), Map.of(), "run ${step.A}");
        assertThat(r.errors()).hasSize(1);
        assertThat(r.command()).isEqualTo("run ${step.A}");
    }

    @Test
    void 命令渲染_变量不存在原样保留并报错() {
        VariableChainResolver.Rendered r =
                VariableChainResolver.renderCommand(Chain.empty(), Map.of(), "run ${nope}");
        // ${nope} 被解析成 source=nope → 不是合法来源关键字 → 格式错误而非"变量不存在"
        assertThat(r.errors()).hasSize(1);
        assertThat(r.command()).isEqualTo("run ${nope}");
    }

    @Test
    void 命令渲染_无引用原样返回() {
        VariableChainResolver.Rendered r =
                VariableChainResolver.renderCommand(Chain.empty(), Map.of(), "echo hi");
        assertThat(r.command()).isEqualTo("echo hi");
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void 命令为null_原样返回null不报错() {
        VariableChainResolver.Rendered r = VariableChainResolver.renderCommand(Chain.empty(), Map.of(), null);
        assertThat(r.command()).isNull();
        assertThat(r.errors()).isEmpty();
    }

    // ── 元信息 ───────────────────────────────────────────────

    @Test
    void 六层顺序与docs一致() {
        assertThat(VariableChainResolver.layerLabels()).containsExactly(
                "平台变量", "项目参数", "工作流参数", "触发时参数", "上游步骤输出", "步骤参数");
    }

    @Test
    void 空链空参数_得到空结果不报错() {
        Result r = VariableChainResolver.resolve(Chain.empty(), null, null);
        assertThat(r.resolvedParams()).isEmpty();
        assertThat(r.snapshotParams()).isEmpty();
        assertThat(r.sources()).isEmpty();
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void 同一参数被解析后_溯源ref指向被点名的引用原文() {
        Chain c = chain(Map.of("清洗", Map.of("file_path", "/data/x")), Map.of());
        Result r = VariableChainResolver.resolve(c, Map.of("in", "${step.清洗.output.file_path}"), Set.of());
        assertThat(r.resolvedParams()).containsEntry("in", "/data/x");
        assertThat(r.sources().get("in").ref()).isEqualTo("${step.清洗.output.file_path}");
        assertThat(r.errors()).isEmpty();
    }
}
