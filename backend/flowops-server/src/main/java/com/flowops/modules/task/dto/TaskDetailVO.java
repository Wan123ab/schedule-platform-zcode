package com.flowops.modules.task.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务详情出参（CONTRACT §7：{@code task} 全字段 + {@code diagnosisInfo} + {@code variableSnapshot}）。
 *
 * <p>继承 {@link TaskVO} 而不是另立一份平铺字段：列表与详情的主体完全一致，
 * 只有这两项"重字段"是详情独有。分开后若哪天给主体加一列，两个端点自动同步，
 * 不会出现"列表有、详情漏"的分叉。</p>
 *
 * <p><b>两个字段为什么是 {@code Object} 而不是 String</b>：库里是 jsonb，实体以
 * String 承载（{@code JsonbTypeHandler}），但对外必须是<b>嵌套 JSON 对象/数组</b>。
 * 若原样把 String 出网，响应里会是一个被引号包裹的 JSON 字符串（前端得二次
 * {@code JSON.parse}），这与契约不符。故 Service 解析后填入 {@code Object}，
 * 坏数据（历史脏行）解析失败时给 {@code null} 而非让查询整体 500。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskDetailVO extends TaskVO {

    /** 6 层变量解析后的最终值快照（PRD §10.0.4；敏感值已脱敏，不落原文 M-07） */
    private Object variableSnapshot;

    /** 调度诊断（写入时的快照；实时诊断走 {@code GET /tasks/{id}/diagnosis}） */
    private Object diagnosisInfo;
}
