package com.flowops.modules.task.ws;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤实时日志 WebSocket（docs/07 §7.5 / PRD §13.2）。
 *
 * <p>路径：{@code /ws/tasks/{taskId}/steps/{stepRowId}/logs?offset=&token=}。</p>
 *
 * <p><b>鉴权（I-07）</b>：token 走 query 参数 —— 企业代理常剥离升级请求的 Header，
 * query 是最稳的握手携带方式；无效 token 直接关闭会话（不留无鉴权日志出口）。</p>
 *
 * <p><b>续传语义</b>：offset = 客户端已收到的最大 seq；服务端从 DB 重放 seq > offset，
 * 之后由 Pub/Sub 转发实时帧 —— 断线不丢、不重。</p>
 */
@Slf4j
@Component
@ServerEndpoint("/ws/tasks/{taskId}/steps/{stepRowId}/logs")
public class TaskLogWebSocket {

    /** JSR-356 端点由容器实例化（非 Spring Bean），注册表经静态桥获取。 */
    private static volatile LogPushRegistry registry;

    public static void bind(LogPushRegistry logRegistry) {
        registry = logRegistry;
    }

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("taskId") String taskId,
                       @PathParam("stepRowId") String stepRowId) {
        long rowId;
        try {
            rowId = Long.parseLong(stepRowId);
        } catch (NumberFormatException e) {
            close(session, "非法 stepRowId");
            return;
        }
        String token = session.getRequestParameterMap()
                .getOrDefault("token", List.of()).stream().findFirst().orElse(null);
        Object loginId = token == null ? null : StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            close(session, "token 无效或已过期");   // 无鉴权不留日志出口（安全红线）
            return;
        }
        long offset = session.getRequestParameterMap()
                .getOrDefault("offset", List.of()).stream().findFirst()
                .map(this::parseOffset).orElse(0L);
        LogPushRegistry reg = registry;
        if (reg == null) {
            close(session, "服务未就绪");
            return;
        }
        reg.register(session, rowId, offset);
        log.info("日志通道建立 task={} step={} offset={} user={}", taskId, stepRowId, offset, loginId);
    }

    @OnClose
    public void onClose(Session session,
                        @PathParam("taskId") String taskId,
                        @PathParam("stepRowId") String stepRowId) {
        LogPushRegistry reg = registry;
        if (reg != null) {
            try {
                reg.unregister(session, Long.parseLong(stepRowId));
            } catch (NumberFormatException ignored) {
                // onOpen 已拦截非法 id；此处仅防御
            }
        }
    }

    private long parseOffset(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void close(Session session, String reason) {
        try {
            session.getBasicRemote().sendText("{\"type\":\"ERROR\",\"message\":\"" + reason + "\"}");
        } catch (Exception ignored) {
            // 对端可能已断开
        }
        try {
            session.close();
        } catch (Exception ignored) {
            // 同上
        }
    }
}
