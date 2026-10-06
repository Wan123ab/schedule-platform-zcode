package com.flowops.executor.client;

import com.flowops.executor.protocol.ExecuteCommand;
import com.flowops.executor.protocol.ExecuteResult;

/**
 * 执行节点通信客户端 SPI。
 * 一期以 SSH 为主（Q-01：SSH 为主，Agent 作为增强可放弃；Q-02：Windows 仅登记展示）。
 *
 * <p>日志通道不经过本接口返回值：stdout/stderr 须按行流式回调，避免长任务全量驻留内存
 * （docs/03 §4.5：双通道 —— 落库 + Redis Pub/Sub → WebSocket）。</p>
 */
public interface ExecutorClient {

    /**
     * 执行前连通性测试（节点管理页「测试连通」按钮，权限点 schedule:node:test）。
     */
    boolean testConnection(String machineIp, String username, String secretMaterial);

    /**
     * 下发执行指令（阻塞直到进程退出或超时）。
     * 实现要求：按行流式读取 stdout/stderr；编码统一 UTF-8；超长行截断（docs/09 M1 风险项）。
     */
    ExecuteResult execute(ExecuteCommand command, LineListener lineListener);

    /** 终止远端进程（任务 STOPPING 收敛，docs/03 §5.2）。 */
    boolean terminate(String machineIp, String dispatchToken);

    /** 行监听：lineType = stdout / stderr / system。 */
    @FunctionalInterface
    interface LineListener {
        void onLine(String lineType, String line);
    }
}
