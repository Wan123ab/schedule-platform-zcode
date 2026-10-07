package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * DAG 里的一条连线（CONTRACT §6.2；DDL {@code workflow_edge}）。
 *
 * <p>两端引用的是 {@link DagStepDef#getStepId()} —— <b>同一请求内</b>的步骤键，
 * 不是数据库主键。服务端保存时的顺序是死的：先删边 → 删步骤 → 插步骤（拿到新主键）
 * → 插边；反过来会在插入时撞 FK（引用到刚被删掉的步骤主键）。</p>
 *
 * <p>服务端会拒绝三种"不构成一张图"的请求（<b>40001</b>，不是 42213）：端点缺失、
 * 端点指向不存在的 {@code stepId}、自环（{@code source == target}）——
 * 这些是请求体本身不合法，真实画布产不出来。业务语义上的"成环"是规则 5，
 * 仍走 42213。</p>
 */
@Data
public class DagEdgeDef {

    @NotBlank(message = "连线缺少起点（sourceStepId）")
    private String sourceStepId;

    @NotBlank(message = "连线缺少终点（targetStepId）")
    private String targetStepId;
}
