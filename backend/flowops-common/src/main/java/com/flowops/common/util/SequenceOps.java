package com.flowops.common.util;

/**
 * Redis 序列自增的最小抽象（INCR 语义）。
 *
 * <p><b>为什么存在</b>：发号（{@link IdGen}）必须全平台同源，但 server 与 scheduler 是
 * 两个互不依赖的模块（D-08），Redis 客户端也不同（server 用 spring-data-redis 的
 * {@code StringRedisTemplate}，scheduler 用 Redisson）。两者对"发号"的需求是同一个
 * Redis INCR —— 抽出本接口，让 IdGen 的编号口径（前缀/日期/定宽）保持单一真源，
 * 客户端差异由各模块在装配时注入。</p>
 */
@FunctionalInterface
public interface SequenceOps {

    /**
     * 对给定 key 执行 INCR（key 不存在时从 0 起步，与 Redis INCR 语义一致）。
     *
     * @return 自增后的值；Redis 不可用时返回 {@code null}（调用方决定降级策略）
     */
    Long increment(String key);
}
