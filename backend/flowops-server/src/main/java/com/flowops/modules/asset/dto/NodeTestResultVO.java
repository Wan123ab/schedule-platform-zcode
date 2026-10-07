package com.flowops.modules.asset.dto;

/**
 * 节点连通性测试结果（docs/07 §5.4 {@code POST /executor-nodes/{id}/test}，权限点 schedule:node:test）。
 *
 * <p>为什么返回结构体而不是 {@code true/false}：UI 需要同时展示耗时与失败原因摘要，
 * 排障时"握手成功但要 8 秒"和"立即失败"是两种完全不同的结论。</p>
 */
public record NodeTestResultVO(boolean success, String message, long elapsedMs) {
}
