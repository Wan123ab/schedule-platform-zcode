package com.flowops.modules.asset.validator;

import com.flowops.common.api.FieldError;
import com.flowops.modules.asset.dto.DryRunRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试运行参数校验器（纯函数，PRD §10.6 / docs/07 §6.3 的 42210 + errors[] 契约）。
 *
 * <p><b>为什么单独成一个纯函数类</b>：算子版本发布后不校验"参数填得对不对"
 * （那时没有运行值），试运行是这套参数模板<b>唯一</b>被真实求值的地方 ——
 * 校验规则会长期演进，写死在 Service 里就只能通过 mock 一堆 Mapper 去测。
 * 这里输入是两个不可变对象（请求 + 参数规格），无 Mapper、无 Spring，可逐条造用例。</p>
 *
 * <p><b>校验与落地的分工</b>：本类只回答"这次提交能不能跑"，不负责合并默认值
 * （那是 {@code OperatorDryRunService} 的事）。分开的好处是错误信息与取值逻辑
 * 不会互相污染：合并逻辑一变，校验结果不该跟着变。</p>
 */
public final class DryRunPlanValidator {

    /** 单次试运行时长上限（PRD §10.6 原文"上限 10 分钟；超时自动终止"）。 */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    private DryRunPlanValidator() {
    }

    /**
     * 参数规格 —— 只在"校验器需要的字段"上取值。
     *
     * <p>刻意不直接吃 {@code OperatorParamDef} 实体：{@code options} 在实体里是 JSON
     * 字符串（jsonb 由 {@code JsonbTypeHandler} 承载），直接塞进来会让本类被迫依赖
     * JSON 库、也就没法再叫纯函数。解析 JSON 的那一步留在 Service。</p>
     */
    public record ParamSpec(String key, String name, String type, boolean required,
                            String defaultValue, boolean runtimeOverridable, boolean sensitive,
                            List<String> choices) {
    }

    /**
     * 校验请求本身与参数值。
     *
     * <p>返回全部错误而不是抛第一个：与上传一致 —— 试运行表单一次提交要把所有填错的地方
     * 一起标红，否则用户要按"提交次数"逐条发现。</p>
     */
    public static List<FieldError> validate(DryRunRequest request, List<ParamSpec> declared) {
        List<FieldError> errors = new ArrayList<>();

        if (request.getExecutorNodeId() == null || request.getExecutorNodeId().isBlank()) {
            errors.add(FieldError.of("executor_node_id", "目标执行节点必填"));
        }
        Integer timeout = request.getTimeoutSeconds();
        if (timeout != null && (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS)) {
            errors.add(FieldError.of("timeout_seconds",
                    "试运行时长为 1~" + MAX_TIMEOUT_SECONDS + " 秒（单次上限 10 分钟）"));
        }

        Map<String, ParamSpec> byKey = new LinkedHashMap<>();
        for (ParamSpec spec : declared) {
            byKey.put(spec.key(), spec);
        }
        Map<String, Object> provided = request.getParams() == null ? Map.of() : request.getParams();

        // ① 模板里没有的参数：多数是拼写错误。放过它就等于"这个值被静默忽略"，
        //    用户会以为命令里注入了它，而实际执行的命令里没有 —— 最难查的一类问题。
        for (String key : provided.keySet()) {
            if (!byKey.containsKey(key)) {
                errors.add(FieldError.of("params." + key, "参数模板中没有该参数: " + key));
            }
        }

        for (ParamSpec spec : declared) {
            Object raw = provided.get(spec.key());
            boolean hasValue = hasValue(raw);
            if (!hasValue) {
                // 没填且没有默认值且必填 → 拦在这里，而不是让远端命令以缺少参数的形式失败
                if (spec.required() && isBlank(spec.defaultValue())) {
                    errors.add(FieldError.of("params." + spec.key(),
                            "缺少必填参数「" + displayName(spec) + "」"));
                }
                continue;
            }
            // ② 声明"不可运行时覆盖"的参数：值必须就是默认值本身
            //    （前端会把默认值预填进来，原样提交不该被判错 —— 只有改动才是越界）
            if (!spec.runtimeOverridable() && !String.valueOf(raw).equals(spec.defaultValue())) {
                errors.add(FieldError.of("params." + spec.key(),
                        "参数「" + displayName(spec) + "」不允许运行时覆盖"));
            }
            String typeError = validateType(spec, raw);
            if (typeError != null) {
                errors.add(FieldError.of("params." + spec.key(), typeError));
            }
        }
        return errors;
    }

    /**
     * 类型值域校验：只做能给出确定答案的三类。
     *
     * <p>TEXT 与 DATETIME 刻意不校验：前者无值域，后者一期没有统一的时间格式真源
     * （{@code rule} 是自由文本，解释它属于二期）。给它们编一套规则只会制造
     * "模板里写着一种格式、校验器认另一种"的新冲突。</p>
     */
    private static String validateType(ParamSpec spec, Object raw) {
        String value = String.valueOf(raw);
        return switch (spec.type() == null ? "TEXT" : spec.type()) {
            case "SINGLE" -> spec.choices().isEmpty() || spec.choices().contains(value)
                    ? null
                    : "参数「" + displayName(spec) + "」的取值必须在候选值内: " + String.join(" / ", spec.choices());
            case "NUMBER" -> isNumeric(value) ? null : "参数「" + displayName(spec) + "」必须是数字";
            case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                    ? null : "参数「" + displayName(spec) + "」必须是 true / false";
            default -> null;
        };
    }

    private static boolean isNumeric(String value) {
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean hasValue(Object raw) {
        return raw != null && !(raw instanceof String s && s.isBlank());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String displayName(ParamSpec spec) {
        return spec.name() != null && !spec.name().isBlank() ? spec.name() : spec.key();
    }
}
