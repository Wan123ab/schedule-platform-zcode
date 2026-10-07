package com.flowops.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业务编号生成器 —— <b>全平台唯一实现</b>（口径见 docs/05 §6.2，归属见 docs/03 的 core/util/IdGen）。
 *
 * <p>为什么必须收口成一处：编号是<b>印在所有日志、审计、对客截图、告警文案上的标识</b>。
 * 一旦各处各写一份，最先崩掉的不是唯一性，而是"可读性约定"—— 出现
 * {@code TASK-20261007-7} 与 {@code TASK-20261007-0007} 两种写法时，人肉比对与
 * 日志检索都会失效。故这里同时锁死三件事：前缀、日期格式、序号定宽。</p>
 *
 * <p>两类编号（docs/05 §6.2）：</p>
 * <ul>
 *   <li>{@link #nextDated(String, String)} —— <b>按日归零</b>式，如 {@code PRJ-20261007-0001}：
 *       用于任务/项目/集群/凭据等量大、且"当天第几个"本身有运维意义的对象；</li>
 *   <li>{@link #next(String, String)} —— <b>全局递增</b>式，如 {@code OP-0001}：
 *       用于算子/工作流等数量可控、且编号希望长期稳定的对象。</li>
 * </ul>
 *
 * <p><b>序号宽度</b>：定宽 4 位（{@code %04d}）。超过 9999 时自然变成 5 位
 * （{@code TASK-20260921-10000}），列宽 varchar(32) 足够；<b>绝不回绕、绝不截断</b>——
 * 回绕会直接撞唯一索引，截断会直接丢信息。</p>
 *
 * <p><b>Redis 不可用时</b>：不抛异常阻塞主流程，而是降级为时间戳低位段，
 * 由数据库的 {@code uk_*} 唯一索引作最终兜底（这条降级策略沿用 M1 的既有语义，
 * 不是本类新引入的取舍）。</p>
 */
@Component
@RequiredArgsConstructor
public class IdGen {

    /** Redis 序列键统一前缀：{@code flowops:seq:<seqName>[:<yyyyMMdd>]} */
    private static final String SEQ_PREFIX = "flowops:seq:";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 序号定宽（见类注释：只定最小宽度，超出不截断） */
    private static final String SEQ_FORMAT = "%04d";

    private final StringRedisTemplate redis;

    /**
     * 日期式编号：{@code <prefix>-<yyyyMMdd>-<####>}，序号按日归零。
     *
     * @param prefix  业务前缀，如 {@code PRJ} / {@code CL} / {@code TASK}
     * @param seqName 序列名，如 {@code prj} / {@code cl}（对应 Redis key {@code flowops:seq:prj:20261007}）
     */
    public String nextDated(String prefix, String seqName) {
        String date = DATE.format(LocalDate.now());
        return prefix + "-" + date + "-" + formatSeq(increment(seqName + ":" + date));
    }

    /**
     * 全局式编号：{@code <prefix>-<####>}，不按日归零。
     *
     * @param prefix  业务前缀，如 {@code OP} / {@code WF}
     * @param seqName 序列名，如 {@code op}（对应 Redis key {@code flowops:seq:op}）
     */
    public String next(String prefix, String seqName) {
        return prefix + "-" + formatSeq(increment(seqName));
    }

    private long increment(String seqName) {
        Long seq = redis.opsForValue().increment(SEQ_PREFIX + seqName);
        return seq != null ? seq : System.currentTimeMillis() % 100000;
    }

    private String formatSeq(long seq) {
        return String.format(SEQ_FORMAT, seq);
    }
}
