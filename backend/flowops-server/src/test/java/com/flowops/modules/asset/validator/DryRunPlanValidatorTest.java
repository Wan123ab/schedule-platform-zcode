package com.flowops.modules.asset.validator;

import com.flowops.common.api.FieldError;
import com.flowops.modules.asset.dto.DryRunRequest;
import com.flowops.modules.asset.validator.DryRunPlanValidator.ParamSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 试运行参数校验器单测（PRD §10.6 / 42210 的 errors[] 契约）。
 *
 * <p>用例名直接写成"什么情况下报/不报"，因为这里每一条都是产品规则，
 * 而规则的实现最容易在重构中被"顺手简化"。</p>
 */
class DryRunPlanValidatorTest {

    private static final String NODE = "EN-0001";

    @Test
    void 未选节点_报executor_node_id() {
        assertThat(fields(validate(request(null, Map.of(), null), List.of())))
                .containsExactly("executor_node_id");
    }

    @Test
    void 超时上限是600秒_601报错600通过() {
        DryRunRequest over = request(NODE, Map.of(), 601);
        DryRunRequest atLimit = request(NODE, Map.of(), 600);

        assertThat(fields(validate(over, List.of()))).containsExactly("timeout_seconds");
        assertThat(validate(atLimit, List.of())).isEmpty();
        assertThat(DryRunPlanValidator.MAX_TIMEOUT_SECONDS).isEqualTo(600);
    }

    @Test
    void 超时为0或负数_报错() {
        assertThat(fields(validate(request(NODE, Map.of(), 0), List.of()))).containsExactly("timeout_seconds");
        assertThat(fields(validate(request(NODE, Map.of(), -5), List.of()))).containsExactly("timeout_seconds");
    }

    @Test
    void 超时留空_不报错_由服务层按版本默认值与上限兜底() {
        assertThat(validate(request(NODE, Map.of(), null), List.of())).isEmpty();
    }

    @Test
    void 必填参数没填但有默认值_不报错() {
        List<ParamSpec> declared = List.of(spec("biz_date", "TEXT", true, "2026-10-08"));

        assertThat(validate(request(NODE, Map.of(), null), declared)).isEmpty();
    }

    @Test
    void 必填参数没填且无默认值_报params下的key() {
        List<ParamSpec> declared = List.of(spec("input_path", "TEXT", true, null));

        assertThat(fields(validate(request(NODE, Map.of(), null), declared)))
                .containsExactly("params.input_path");
    }

    @Test
    void 必填参数传空白串_视为没填() {
        List<ParamSpec> declared = List.of(spec("input_path", "TEXT", true, null));

        assertThat(fields(validate(request(NODE, Map.of("input_path", "   "), null), declared)))
                .containsExactly("params.input_path");
    }

    /**
     * 拼写错误保护：模板外参数必须报错。
     *
     * <p>放过它的后果是"用户以为这个值注入了命令，实际没有"—— 命令照跑、结果不对，
     * 这类问题在日志里几乎无法回溯。</p>
     */
    @Test
    void 模板里没有的参数_报错而不是静默忽略() {
        List<ParamSpec> declared = List.of(spec("input_path", "TEXT", false, null));

        assertThat(fields(validate(request(NODE, Map.of("input_paht", "/data"), null), declared)))
                .containsExactly("params.input_paht");
    }

    @Test
    void 不可运行时覆盖的参数_原样提交默认值算合法() {
        List<ParamSpec> declared = List.of(
                new ParamSpec("master", "置主", "TEXT", false, "yarn", false, false, List.of()));

        assertThat(validate(request(NODE, Map.of("master", "yarn"), null), declared)).isEmpty();
    }

    @Test
    void 不可运行时覆盖的参数_改成别的值被拒() {
        List<ParamSpec> declared = List.of(
                new ParamSpec("master", "置主", "TEXT", false, "yarn", false, false, List.of()));

        assertThat(fields(validate(request(NODE, Map.of("master", "local"), null), declared)))
                .containsExactly("params.master");
    }

    @Test
    void SINGLE类型_取值必须在候选值内() {
        List<ParamSpec> declared = List.of(
                new ParamSpec("mode", "模式", "SINGLE", false, "fast", true, false, List.of("fast", "safe")));

        assertThat(validate(request(NODE, Map.of("mode", "fast"), null), declared)).isEmpty();
        assertThat(fields(validate(request(NODE, Map.of("mode", "turbo"), null), declared)))
                .containsExactly("params.mode");
    }

    @Test
    void NUMBER类型_必须是数字() {
        List<ParamSpec> declared = List.of(spec("partitions", "NUMBER", false, null));

        assertThat(validate(request(NODE, Map.of("partitions", 200), null), declared)).isEmpty();
        assertThat(validate(request(NODE, Map.of("partitions", "200"), null), declared)).isEmpty();
        assertThat(validate(request(NODE, Map.of("partitions", "1.5"), null), declared)).isEmpty();
        assertThat(fields(validate(request(NODE, Map.of("partitions", "两百"), null), declared)))
                .containsExactly("params.partitions");
    }

    @Test
    void BOOLEAN类型_只认true或false() {
        List<ParamSpec> declared = List.of(spec("dry", "BOOLEAN", false, null));

        assertThat(validate(request(NODE, Map.of("dry", true), null), declared)).isEmpty();
        assertThat(validate(request(NODE, Map.of("dry", "TRUE"), null), declared)).isEmpty();
        assertThat(fields(validate(request(NODE, Map.of("dry", "yes"), null), declared)))
                .containsExactly("params.dry");
    }

    /** TEXT / DATETIME 刻意不做值域校验（rule 是自由文本，解释它属于二期）。 */
    @Test
    void TEXT与DATETIME不做值域校验() {
        List<ParamSpec> declared = List.of(
                spec("note", "TEXT", false, null), spec("run_at", "DATETIME", false, null));

        assertThat(validate(request(NODE, Map.of("note", "任意文本", "run_at", "下午三点"), null), declared))
                .isEmpty();
    }

    @Test
    void 一次收集全部错误_不按提交次数逐条发现() {
        List<ParamSpec> declared = List.of(
                spec("input_path", "TEXT", true, null),
                spec("partitions", "NUMBER", false, null));

        List<String> fields = fields(validate(request(null, Map.of("partitions", "abc"), 9999), declared));

        assertThat(fields).containsExactlyInAnyOrder(
                "executor_node_id", "timeout_seconds", "params.input_path", "params.partitions");
    }

    @Test
    void 无参数模板时不校验参数() {
        assertThat(validate(request(NODE, Map.of(), null), List.of())).isEmpty();
    }

    // ── 夹具 ────────────────────────────────────────────────

    private static List<FieldError> validate(DryRunRequest request, List<ParamSpec> declared) {
        return DryRunPlanValidator.validate(request, declared);
    }

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    private static ParamSpec spec(String key, String type, boolean required, String defaultValue) {
        return new ParamSpec(key, key, type, required, defaultValue, true, false, List.of());
    }

    private static DryRunRequest request(String nodeId, Map<String, Object> params, Integer timeout) {
        DryRunRequest request = new DryRunRequest();
        request.setExecutorNodeId(nodeId);
        request.setParams(params);
        request.setTimeoutSeconds(timeout);
        return request;
    }
}
