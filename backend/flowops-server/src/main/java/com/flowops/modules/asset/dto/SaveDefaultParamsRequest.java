package com.flowops.modules.asset.dto;

import lombok.Data;

import java.util.Map;

/**
 * 「将本次试运行参数另存为该版本的默认值」请求（PRD §10.6 的后续动作）。
 *
 * <p>只承载"值"，不承载结构：参数 key / 类型 / 必填 / 是否敏感都不在请求体里 ——
 * 那些是版本契约的一部分，发布后冻结（D-11）。前端把试运行表单里的值原样提交即可，
 * 但服务端只挑出模板里已声明的 key 落地。</p>
 */
@Data
public class SaveDefaultParamsRequest {

    /** 参数 key → 新默认值；值为空串表示清除该默认值。 */
    private Map<String, String> params;
}
