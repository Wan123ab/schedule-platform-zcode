package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.TaskLogRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 日志表 Mapper（task_log，按日分区 docs/05 §7.2）。
 *
 * <p><b>为何不用 BaseMapper</b>：分区表主键是复合键 (id, log_time)，MP 单列 @TableId 不适配；
 * 且日志只有追加与按 seq 续读两种访问形态，手写 SQL 更窄更快。SQL 见 XML。</p>
 */
@Mapper
public interface TaskLogMapper {

    /** 批量追加（flusher 每 500ms 一批；log_time 由 DB default now() 逐行填充）。 */
    int batchInsert(@Param("rows") List<TaskLogRow> rows);

    /** 按 seq 续读（WebSocket 连接建立/断线重连的重放依据，PRD §13.2 断线不丢不重）。 */
    List<TaskLogRow> selectAfterSeq(@Param("taskStepId") Long taskStepId,
                                    @Param("afterSeq") long afterSeq,
                                    @Param("limit") int limit);

    /**
     * 按行偏移分页读（HTTP 日志端点 {@code GET /tasks/{id}/steps/{sid}/logs?offset&limit}）。
     *
     * <p><b>为什么不用 {@link #selectAfterSeq} 代替</b>：那个是"从某个 seq 之后续读"，
     * 用于流式重放；HTTP 端点对外承诺的是"第 offset 行起的 limit 行"，与"seq 之后"
     * 在 seq 有空洞时并不等价。这里显式用 {@code LIMIT/OFFSET}，语义与契约一致。</p>
     */
    List<TaskLogRow> selectRange(@Param("taskStepId") Long taskStepId,
                                 @Param("offset") long offset,
                                 @Param("limit") int limit);

    /** 该步骤的日志行总数（HTTP 端点出参 {@code totalLines}，前端据此判断是否还有下一页）。 */
    long countByTaskStepId(@Param("taskStepId") Long taskStepId);
}
