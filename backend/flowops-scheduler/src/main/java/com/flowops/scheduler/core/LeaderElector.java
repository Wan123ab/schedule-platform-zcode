package com.flowops.scheduler.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 选主（D-21，docs/06 §10.1）：Redisson 分布式锁 + 看门狗（默认 TTL 30s 自动续期）。
 * 常备 2 副本（1 活 1 备）； standby 每 10s 尝试抢锁，切换 ≤ 45s（N-2）。
 */
@Slf4j
@Component
public class LeaderElector implements DisposableBean {

    public static final String LOCK_KEY = "flowops:scheduler:leader";

    private final RedissonClient redisson;
    private final ScheduledExecutorService retryPool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "leader-election");
                t.setDaemon(true);
                return t;
            });

    @Getter
    private final AtomicBoolean leader = new AtomicBoolean(false);

    private RLock lock;

    @Value("${flowops.scheduler.instance-id:}")
    private String instanceId;

    public LeaderElector(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = "sched-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        tryAcquire();
        // standby 周期性抢锁（docs/06 §10.1：10s 间隔）
        retryPool.scheduleWithFixedDelay(this::tryAcquire, 10, 10, TimeUnit.SECONDS);
        log.info("选主启动 instance={} lock={}", instanceId, LOCK_KEY);
    }

    private void tryAcquire() {
        if (leader.get()) {
            return;
        }
        try {
            lock = redisson.getLock(LOCK_KEY);
            // tryLock(0, ...)：不等待；看门狗自动续期（租约剩余见 /platform/health lease_remaining_seconds）
            boolean acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (acquired) {
                leader.set(true);
                log.info("✅ 成为调度主 instance={}", instanceId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("抢锁失败（Redis 抖动？）instance={}", instanceId, e);
        }
    }

    public boolean isLeader() {
        return leader.get();
    }

    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public void destroy() {
        retryPool.shutdownNow();
        if (leader.get() && lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放主锁 instance={}", instanceId);
        }
    }
}
