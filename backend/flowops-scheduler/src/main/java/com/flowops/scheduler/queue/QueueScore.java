package com.flowops.scheduler.queue;

/**
 * 就绪队列 ZSet 的复合 score 构造（docs/06 §4.2，G1 的核心）。
 *
 * <p><b>需求</b>：优先级降序 + 同优先级 FIFO。Redis ZSet 只支持按 score <b>升序</b>取（ZRANGE），
 * 所以要构造一个"越小越先出队"的分数：</p>
 *
 * <pre>score = (MAX_PRIORITY - priority) × SEQ_WEIGHT + enqueueSeq</pre>
 *
 * <p><b>两个被文档钉死的细节（各对应一次事故推演）：</b></p>
 * <ul>
 *   <li><b>序号用 Redis INCR 而非时间戳</b>（M-09）：时间戳在同毫秒并列，ZSet 转按 member
 *       字典序排序，FIFO 顺序不确定；INCR 严格单调。</li>
 *   <li><b>SEQ_WEIGHT = 2^40 而非 10 亿</b>（v3 评审修订）：按 100 次/秒入队估算，10 亿权重的
 *       序号约 116 天溢出到相邻优先级区间 —— 低优步骤排到高优前面，是<b>静默的正确性事故</b>；
 *       2^40 约 348 年。且 100 × 2^40 ≈ 1.1e14 ≪ Long.MAX_VALUE，优先级区间不溢出。</li>
 * </ul>
 */
public final class QueueScore {

    /** 优先级上限（E-07 定案：0~100，数值越大越优先，默认 0）。 */
    public static final int MAX_PRIORITY = 100;

    /**
     * 序号位权重：2^40 ≈ 1.1e12。
     * ⚠️ 改小它之前先读类注释的事故推演 —— 这个数字不是拍脑袋的。
     */
    public static final long SEQ_WEIGHT = 1L << 40;

    private QueueScore() {
    }

    /**
     * 构造出队 score（越小越先出队）。
     *
     * @param priority    任务优先级 0~100，越大越优先
     * @param enqueueSeq  Redis INCR 全局递增序号（M-09，非时间戳）
     */
    public static long of(int priority, long enqueueSeq) {
        if (priority < 0 || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("priority 超出 [0," + MAX_PRIORITY + "]: " + priority);
        }
        return (long) (MAX_PRIORITY - priority) * SEQ_WEIGHT + enqueueSeq;
    }

    /** 从 score 反解序号所在优先级（诊断/观测用，如校验 ZSet 内容是否与 DB 一致）。 */
    public static int priorityOf(long score) {
        return (int) (MAX_PRIORITY - score / SEQ_WEIGHT);
    }
}
