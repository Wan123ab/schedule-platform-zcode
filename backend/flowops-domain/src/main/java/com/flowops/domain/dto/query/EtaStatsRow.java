package com.flowops.domain.dto.query;

import lombok.Data;

/**
 * 队列 ETA 统计行（docs/06 §5.5）。
 *
 * <p>一条 SQL 同时取「中位排队时长」与「样本数」——样本数不是可选项：
 * 样本 &lt; 20 时 ETA 必须显示「数据不足，无法估算」（docs/00 E-05），
 * 只有中位数没有样本数就无法执行这条闸门。</p>
 */
@Data
public class EtaStatsRow {

    /** 中位排队时长（秒）。无样本时为 0，配合 {@link #sampleCount} 判读 */
    private Double medianWaitSeconds;

    /** 近 7 天样本数（start_time 非空且非回填的任务数） */
    private Long sampleCount;
}
