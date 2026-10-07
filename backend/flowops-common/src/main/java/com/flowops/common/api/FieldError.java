package com.flowops.common.api;

/**
 * 字段级错误项 —— 承载 docs/07 §4.2 里 {@code errors[]{field,message}} 的响应结构。
 *
 * <p><b>为什么要有这个类型</b>：42210（算子上传校验）与 42213（DAG 校验）都要求
 * "逐字段/逐规则"回填错误，前端据此把表单对应项标红。若各处用
 * {@code Map.of("field", .., "message", ..)} 临时拼装，字段名就会在不同域里漂移成
 * {@code fieldName}/{@code key}/{@code name}，前端不得不为每个域写一套解析。</p>
 */
public record FieldError(String field, String message) {

    public static FieldError of(String field, String message) {
        return new FieldError(field, message);
    }
}
