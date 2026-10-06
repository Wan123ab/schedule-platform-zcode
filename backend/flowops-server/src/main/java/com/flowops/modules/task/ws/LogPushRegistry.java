package com.flowops.modules.task.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.dto.query.TaskLogRow;
import com.flowops.domain.mapper.task.TaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志推送注册表（docs/07 §7.5 WebSocket 协议的 server 侧枢纽）。
 *
 * <p><b>职责</b>：① 会话登记（stepRowId → 在线会话集）；② 连接建立时按 offset 从 DB 重放
 * （PRD §13.2：断线不丢日志、不重复推送）；③ Pub/Sub 回调时向订阅会话转发实时帧。</p>
 *
 * <p><b>脱敏在服务端执行</b>（PRD §13.3 硬要求）：本类是日志出网的唯一出口，
 * 脱敏过滤器（M2 随运维视图落地）在此挂载，前端不接触原文。</p>
 */
@Slf4j
@Component
public class LogPushRegistry {

    private static final int REPLAY_BATCH = 1000;

    private final TaskLogMapper taskLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** stepRowId → 会话集（JSR-356 Session 非线程安全发送，写操作由本类串行化）。 */
    private final Map<Long, java.util.Set<jakarta.websocket.Session>> sessions = new ConcurrentHashMap<>();

    public LogPushRegistry(TaskLogMapper taskLogMapper) {
        this.taskLogMapper = taskLogMapper;
    }

    /**
     * 连接建立：登记会话并重放 seq > offset 的历史（一次握手完成"历史 + 实时"衔接）。
     */
    public void register(jakarta.websocket.Session session, long stepRowId, long offset) {
        sessions.computeIfAbsent(stepRowId, k -> ConcurrentHashMap.newKeySet()).add(session);
        replay(session, stepRowId, offset);
    }

    public void unregister(jakarta.websocket.Session session, long stepRowId) {
        var set = sessions.get(stepRowId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(stepRowId, set);
            }
        }
    }

    /** Pub/Sub 回调（Redis 监听线程）：转发实时帧到订阅会话。 */
    public void publish(long stepRowId, String frameJson) {
        var set = sessions.get(stepRowId);
        if (set == null || set.isEmpty()) {
            return;   // 无人订阅：帧已落库，重连可补（PRD §13.2 语义）
        }
        for (jakarta.websocket.Session session : set) {
            sendQuietly(session, frameJson);
        }
    }

    private void replay(jakarta.websocket.Session session, long stepRowId, long offset) {
        try {
            long cursor = offset;
            List<TaskLogRow> batch;
            do {
                batch = taskLogMapper.selectAfterSeq(stepRowId, cursor, REPLAY_BATCH);
                for (TaskLogRow row : batch) {
                    sendQuietly(session, frame(row));
                    cursor = row.getSeq();
                }
            } while (batch.size() == REPLAY_BATCH);   // 分批重放，避免大日志一次性撑爆首帧
        } catch (Exception e) {
            log.warn("日志重放失败 step={} offset={}", stepRowId, offset, e);
        }
    }

    /** 与采集侧同构的帧格式（docs/07 §7.5：{type, seq, stream, content}，键名字面 snake）。 */
    private String frame(TaskLogRow row) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "LOG", "seq", row.getSeq(), "stream", row.getStream(), "content", row.getContent()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendQuietly(jakarta.websocket.Session session, String text) {
        try {
            synchronized (session) {
                session.getBasicRemote().sendText(text);
            }
        } catch (Exception e) {
            log.debug("推送失败（会话可能已断开）: {}", e.getMessage());
        }
    }
}
