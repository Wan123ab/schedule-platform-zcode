package com.flowops.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编号生成器单测（docs/05 §6.2 的口径断言）。
 *
 * <p>为什么值得单独测：编号是 <b>印在日志/审计/对客截图上的标识</b>。格式漂移不会让任何
 * 功能失败，只会让"人肉比对与日志检索"悄悄失效 —— 这种退化只有断言字面量才挡得住。
 * 所以下面直接断言完整编号（如 {@code OP-0042}），而不是"以 OP- 开头"：
 * 让格式变化必须显式改测试，而不是无声滑过。</p>
 *
 * <p>mock 一律在 {@link BeforeEach} 里新建：mock 若做成静态共享字段，交互记录会跨用例累积，
 * 断言将变成"依赖执行顺序"的脆弱测试。</p>
 */
class IdGenTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    private IdGen idGenReturning(Long seq) {
        when(ops.increment(anyString())).thenReturn(seq);
        return new IdGen(redis);
    }

    @Test
    void 日期式编号_形如前缀与日期与四位序号() {
        IdGen gen = idGenReturning(7L);

        // 日期取当天 → 只断言结构，不硬编码日期，否则跨零点跑 CI 会随机挂
        assertThat(gen.nextDated("PRJ", "prj")).matches("PRJ-\\d{8}-0007");
    }

    @Test
    void 全局式编号_形如前缀与四位序号() {
        assertThat(idGenReturning(1L).next("OP", "op")).isEqualTo("OP-0001");
        assertThat(idGenReturning(42L).next("OP", "op")).isEqualTo("OP-0042");
        assertThat(idGenReturning(9999L).next("OP", "op")).isEqualTo("OP-9999");
    }

    /** 超过 9999 时自然进位为 5 位：docs/05 §6.2 明确"不回绕、不截断"（回绕必撞唯一索引）。 */
    @Test
    void 序号超过四位_自然进位而非回绕() {
        assertThat(idGenReturning(10000L).next("OP", "op")).isEqualTo("OP-10000");
    }

    /** Redis 不可用时降级（而非抛异常阻塞主流程），唯一性交给 DB 的 uk_* 索引兜底。 */
    @Test
    void Redis不可用_降级为时间戳段且不抛异常() {
        String id = idGenReturning(null).next("OP", "op");

        assertThat(id).startsWith("OP-").doesNotContain("null");
    }

    /** 不同序列名必须落到不同 Redis key —— 否则两个业务域共用一条序号，编号会互相跳号。 */
    @Test
    void 不同序列名_落到不同key() {
        IdGen gen = idGenReturning(1L);

        gen.next("OP", "op");
        gen.next("WF", "wf");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, atLeastOnce()).increment(keys.capture());
        assertThat(keys.getAllValues()).contains("flowops:seq:op", "flowops:seq:wf");
    }

    /** 日期式编号的 key 必须带日期后缀，否则"按日归零"会退化成全局累加。 */
    @Test
    void 日期式编号_key带日期后缀() {
        IdGen gen = idGenReturning(1L);

        gen.nextDated("TASK", "task");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops).increment(keys.capture());
        assertThat(keys.getValue()).matches("flowops:seq:task:\\d{8}");
    }
}
