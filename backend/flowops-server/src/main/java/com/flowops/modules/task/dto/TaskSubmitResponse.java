package com.flowops.modules.task.dto;

/**
 * 提交出参（docs/07 §6.4 的响应分流：两种"成功"用一个响应表达）。
 * deferred=true 表示 QUEUE/项目额度排队（code 仍为 0，前端渲染"已排队"，不是错误）。
 */
public record TaskSubmitResponse(
        String taskId,
        String status,
        boolean deferred,
        Long queuePosition) {
}
