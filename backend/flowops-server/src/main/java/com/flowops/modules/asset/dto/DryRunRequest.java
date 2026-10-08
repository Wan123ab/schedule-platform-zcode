package com.flowops.modules.asset.dto;

import lombok.Data;

import java.util.Map;

/**
 * 算子试运行请求（PRD §10.6；docs/09 M3「选节点 + 实时日志 + 退出码」）。
 *
 * <p><b>无 jakarta 注解校验，与上传同口径</b>：试运行失败要返回
 * <b>42210 + errors[]</b>（逐字段回填到表单），而注解式校验失败会走 40001。
 * 同一张表单上并存两种错误码会让前端写两套渲染，故校验统一由
 * {@code DryRunPlanValidator} 收集成 {@code List<FieldError>} 一次返回。</p>
 */
@Data
public class DryRunRequest {

    /** 目标执行节点业务编号 {@code EN-####}（内部主键不出网，D-27）。 */
    private String executorNodeId;

    /**
     * 本次试运行的参数值，key = {@code operator_param_def.param_key}。
     *
     * <p>用 {@code Map<String,Object>} 而不是 {@code Map<String,String>}：参数类型有
     * NUMBER / BOOLEAN，前端按类型填的 JSON 原样传过来即可，服务端不做字符串化，
     * 让"整串单引用透传原类型"的解析语义在试运行里也成立（与真实下发一致）。</p>
     */
    private Map<String, Object> params;

    /** 超时秒数；留空取算子版本的 {@code default_timeout_seconds}，再退化为平台上限 600。 */
    private Integer timeoutSeconds;
}
