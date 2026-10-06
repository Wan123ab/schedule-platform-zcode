package com.flowops.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等（D-17，docs/07 §7.2）：Idempotency-Key 请求头 + Redis 去重 24h。
 * 必带场景（后端强制）：POST /tasks、POST /backfills、两个 publish 端点；
 * 口径为「前端必带、后端不强拒」——未带 key 的写操作正常执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 快照 TTL（秒），默认 24h。 */
    int ttlSeconds() default 86400;

    /** true 时未携带 Idempotency-Key 的请求直接拒绝（仅 4 个高危端点）。 */
    boolean required() default false;
}
