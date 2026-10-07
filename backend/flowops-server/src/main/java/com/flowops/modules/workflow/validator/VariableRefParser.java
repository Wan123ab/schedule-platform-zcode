package com.flowops.modules.workflow.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量引用解析器（docs/07 §9.1 的 D-20 语法）。
 *
 * <p><b>语法</b>（与 docs/07 §9.1 的 EBNF 逐条对应）：</p>
 * <pre>
 *   var_ref  = "${" source "." path "}" ;
 *   source   = "step" | "trigger" | "project" | "platform" | "task" ;
 *   path     = step_path | plain_path ;
 *   step_path = step_name ".output." var_name ;
 *   step_name = ident | quoted ;     (* 含特殊字符时用引号 *)
 * </pre>
 *
 * <p><b>为什么自己写而不是上模板引擎</b>：这里要的是<b>校验</b>而不是渲染。
 * 模板引擎（SpEL / FreeMarker）的失败模式是"渲染时才发现"，而本解析器要在
 * <b>发布前</b>回答三个问题：语法对不对、被引用的步骤在不在、那个步骤是不是
 * 引用的（传递）上游。后者需要把引用解析成结构化结果再和 DAG 图比对，
 * 引擎给不出来。</p>
 *
 * <p><b>本类只做语法与拆分</b>：可达性判定在 {@link DagValidator} 里（需要图）。
 * 分开的理由是两者可以独立测 —— 语法用例不需要构造 DAG。</p>
 */
public final class VariableRefParser {

    /** 引用的合法来源关键字（新增来源要去改 docs/07 §9.1，而不是只改这里）。 */
    private static final List<String> SOURCES = List.of("step", "trigger", "project", "platform", "task");

    /** 匹配 ${...}，非贪婪；不吃换行（参数里的换行沿用原样，不应跨行匹配引用）。 */
    private static final Pattern REF = Pattern.compile("\\$\\{([^{}]*)}");

    private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern CJK = Pattern.compile("\\p{IsHan}+");

    private VariableRefParser() {
    }

    /**
     * 一个已解析出的引用。
     *
     * @param raw       原文（含 {@code ${}}），用于错误提示里原样回显
     * @param source    来源关键字；语法非法时为 {@code null}
     * @param stepName  仅 {@code source=step} 时有值（已去掉引号）
     * @param varName   仅 {@code source=step} 时有值（{@code output} 之后的变量名）
     * @param error     语法错误说明；合法时为 {@code null}
     */
    public record VariableRef(String raw, String source, String stepName, String varName, String error) {

        public boolean valid() {
            return error == null;
        }

        public boolean isStepOutput() {
            return valid() && "step".equals(source);
        }
    }

    /**
     * 从一段文本里抽出全部引用。
     *
     * <p>未闭合的 {@code ${}}（只有左括号没右括号）也会被报出来：它是编辑中途最常见的
     * 手误，若不报，用户会以为"这个引用没生效"而不是"我少打了个右括号"。</p>
     */
    public static List<VariableRef> parse(String text) {
        List<VariableRef> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }
        Matcher m = REF.matcher(text);
        while (m.find()) {
            refs.add(parseOne(m.group(), m.group(1)));
        }
        // 未闭合检测：把已匹配到的片段挖掉后，若还剩 "${" 就是没闭合的
        String rest = REF.matcher(text).replaceAll("");
        int open = rest.indexOf("${");
        if (open >= 0) {
            refs.add(new VariableRef(rest.substring(open), null, null, null, "变量引用未闭合，缺少 '}'"));
        }
        return refs;
    }

    /**
     * 递归遍历参数值（Map / List / 字符串）收集引用。
     *
     * <p>参数是 JSONB 结构，值可能是嵌套对象或数组，引用可能藏在任意一层 ——
     * 只扫顶层字符串会漏掉最常见的 {@code {"args": ["${step.A.output.x}"]}}。</p>
     */
    public static List<VariableRef> parseParams(Object value) {
        List<VariableRef> refs = new ArrayList<>();
        collect(value, refs);
        return refs;
    }

    private static void collect(Object value, List<VariableRef> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            out.addAll(parse(s));
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(e.getKey(), out);
                collect(e.getValue(), out);
            }
        } else if (value instanceof Iterable<?> it) {
            for (Object o : it) {
                collect(o, out);
            }
        }
    }

    private static VariableRef parseOne(String raw, String body) {
        String inner = body.trim();
        if (inner.isEmpty()) {
            return new VariableRef(raw, null, null, null, "变量引用为空");
        }
        int dot = inner.indexOf('.');
        if (dot <= 0) {
            return new VariableRef(raw, null, null, null,
                    "变量引用缺少来源前缀，应为 ${step.步骤名.output.变量名}");
        }
        String source = inner.substring(0, dot);
        String path = inner.substring(dot + 1);
        if (!SOURCES.contains(source)) {
            return new VariableRef(raw, null, null, null,
                    "未知的变量来源 '" + source + "'，可用：" + String.join(" / ", SOURCES));
        }
        if (!"step".equals(source)) {
            // trigger/project/platform/task 类引用一期不做可达性判定（它们不来自 DAG 节点）
            return new VariableRef(raw, source, null, null, null);
        }
        return parseStepPath(raw, path);
    }

    /** {@code step.步骤名.output.变量名} 的拆分；步骤名可被引号包裹以容纳特殊字符。 */
    private static VariableRef parseStepPath(String raw, String path) {
        String stepName;
        String rest;
        boolean quoted = path.startsWith("\"");
        if (quoted) {
            int end = path.indexOf('"', 1);
            if (end < 0) {
                return new VariableRef(raw, "step", null, null, "步骤名引号未闭合");
            }
            stepName = path.substring(1, end);
            rest = path.substring(end + 1);
        } else {
            int idx = path.indexOf('.');
            stepName = idx < 0 ? path : path.substring(0, idx);
            rest = idx < 0 ? "" : path.substring(idx);
        }
        if (stepName.isEmpty()) {
            return new VariableRef(raw, "step", null, null, "步骤名为空");
        }
        // 只有**未加引号**的名字才要求"纯标识符或纯中文"。
        // 走引号路径时特殊字符本就是被允许的（语法里 quoted 的存在意义），
        // 再拿同一把尺子量一次会把合法写法判成非法 —— 这个错误最初就是被
        // VariableRefParserTest#步骤名含特殊字符_用引号包裹 抓出来的。
        if (!quoted && !IDENT.matcher(stepName).matches() && !CJK.matcher(stepName).matches()) {
            return new VariableRef(raw, "step", stepName, null,
                    "步骤名 '" + stepName + "' 含特殊字符，需用引号包裹（如 ${step.\"步骤-2\".output.x}）");
        }
        if (!rest.startsWith(".output.")) {
            return new VariableRef(raw, "step", stepName, null,
                    "步骤引用缺少 .output. 段，应为 ${step." + stepName + ".output.变量名}");
        }
        String varName = rest.substring(".output.".length());
        if (!IDENT.matcher(varName).matches()) {
            return new VariableRef(raw, "step", stepName, varName,
                    "输出变量名 '" + varName + "' 不合法（需以字母或下划线开头）");
        }
        return new VariableRef(raw, "step", stepName, varName, null);
    }
}
