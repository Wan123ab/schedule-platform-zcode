package com.flowops.modules.task.dto;

import lombok.Data;

/**
 * ETA 估算（docs/06 §5.5）。
 *
 * <p><b>展示口径是"约 X 分钟后"，不许精确到秒</b>——那是不可信的承诺（docs 原文）。
 * {@code basis} 是必给的依据说明；样本不足时 {@code estimatedSeconds} 为 {@code null}，
 * {@code basis} 固定为「数据不足，无法估算」（docs/00 E-05）。</p>
 */
@Data
public class DiagnosisEtaVO {

    /** 预计还需等待的秒数（= 中位排队时长 × 队列位置 ÷ 平均并发度）；样本不足时 null */
    private Long estimatedSeconds;

    /** 参与统计的近 7 天样本数 */
    private Long sampleCount;

    /** 估算依据 / 数据不足说明（前端直接展示） */
    private String basis;
}
