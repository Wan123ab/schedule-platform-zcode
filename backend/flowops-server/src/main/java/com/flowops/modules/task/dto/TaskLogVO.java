package com.flowops.modules.task.dto;

import lombok.Data;

/**
 * 步骤日志出参（CONTRACT §7 + docs/07 §7.5：{@code {content, eof, totalLines}}）。
 *
 * <p><b>为什么是"一次性内容"而不是流</b>：这个 HTTP 端点服务"翻看历史日志"
 * （按 offset/limit 分页），实时跟随走 WebSocket（{@code /ws/tasks/{id}/logs}，
 * 帧里带 {@code stream} 字段区分 stdout/stderr）。两条通道各司其职：HTTP 可缓存、
 * 可搜索、可粘贴；WS 才需要"逐行推 + 断线重连按 seq 补"。</p>
 *
 * <p>{@code offset}/{@code limit} 原样回显，便于前端做"还有没有下一页"的判断
 * （{@code eof=true} 或 {@code offset+limit >= totalLines} 都表示到底）。</p>
 */
@Data
public class TaskLogVO {

    /** 本页日志内容（按 seq 升序、以换行拼接） */
    private String content;

    /** 是否已到末尾（本页返回行数 < limit） */
    private boolean eof;

    /** 该步骤日志总行数（日志保留 30 天，PRD §13.2） */
    private long totalLines;

    /** 请求的行偏移（回显） */
    private long offset;

    /** 请求的行数上限（回显） */
    private int limit;
}
