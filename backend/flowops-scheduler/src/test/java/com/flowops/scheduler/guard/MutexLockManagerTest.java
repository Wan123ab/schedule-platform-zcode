package com.flowops.scheduler.guard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MutexLockManager 集成测试（真实 Redis，docs/06 §16"互斥锁单测"行的 Testcontainers 等价实现）。
 *
 * <p>Redis 不可达时<b>自动跳过</b>（Assumptions）—— 本地无 Redis 的开发机不阻塞；
 * CI 的 backend job 挂 redis 服务容器，此测试在 CI 必然执行。</p>
 */
class MutexLockManagerTest {

    private static RedissonClient redisson;
    private static MutexLockManager mutexes;

    @BeforeAll
    static void setUp() {
        assumeTrue(redisAvailable(), "Redis 不可达，跳过互斥锁集成测试（CI 中必然可达）");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        redisson = Redisson.create(config);
        mutexes = new MutexLockManager(redisson);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    private static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 每个用例独立的互斥组名，避免用例间串扰。 */
    private static String group() {
        return "test-mutex-" + UUID.randomUUID();
    }

    @Test
    void 获取与释放_全平台串行() {
        String g = group();
        Duration ttl = Duration.ofHours(1);

        assertThat(mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "task:TASK-A/step:清洗", ttl)).isTrue();
        assertThat(mutexes.holderOf(g)).contains("task:TASK-A/step:清洗");

        // 同名锁第二个竞争者拿不到
        assertThat(mutexes.tryAcquire(g, 200L, 2L, 5, 42L, "task:TASK-B/step:清洗", ttl)).isFalse();
    }

    @Test
    void 拿锁失败_登记为等待者() {
        String g = group();
        mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "holder-A", Duration.ofHours(1));

        mutexes.tryAcquire(g, 200L, 2L, 5, 42L, "holder-B", Duration.ofHours(1));
        mutexes.tryAcquire(g, 300L, 3L, 5, 42L, "holder-C", Duration.ofHours(1));

        assertThat(mutexes.waitersCount(g)).isEqualTo(2);   // §6.3.1：失败者进 waiters ZSet
    }

    @Test
    void 释放_唤醒seq最小的等待者_公平不插队() {
        String g = group();
        mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "holder-A", Duration.ofHours(1));
        mutexes.tryAcquire(g, 300L, 3L, 5, 42L, "holder-C", Duration.ofHours(1));   // 先等
        mutexes.tryAcquire(g, 200L, 2L, 5, 42L, "holder-B", Duration.ofHours(1));   // 后等但 seq 更小

        Optional<MutexLockManager.Waiter> woken = mutexes.release(g, 100L);

        assertThat(woken).isPresent();
        assertThat(woken.get().taskStepId()).isEqualTo(200L);   // seq=2 < seq=3：原序号 FIFO，不是先来先服务
        assertThat(woken.get().enqueueSeq()).isEqualTo(2L);
        assertThat(woken.get().priority()).isEqualTo(5);   // 唤醒重排队需要原优先级
        assertThat(woken.get().queueId()).isEqualTo(42L);
        assertThat(mutexes.holderOf(g)).isEmpty();              // 释放后 holder 清空，由唤醒方重新登记
    }

    @Test
    void 释放_无等待者_返回空() {
        String g = group();
        mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "holder-A", Duration.ofHours(1));

        assertThat(mutexes.release(g, 100L)).isEmpty();
        assertThat(mutexes.waitersCount(g)).isZero();
    }

    @Test
    void 释放幂等_重复释放不抛异常() {
        String g = group();
        mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "holder-A", Duration.ofHours(1));

        mutexes.release(g, 100L);
        mutexes.release(g, 100L);   // docs/06 §6.3：释放必须幂等（步骤终态收敛与超时扫描可能双触发）
        assertThat(mutexes.holderOf(g)).isEmpty();
    }

    @Test
    void 唤醒后重新竞争失败_等待者可重新登记() {
        // §6.3.1 ③：唤醒方 tryAcquire 失败（锁被第三方抢走）→ 原序号重新入 waiters
        String g = group();
        mutexes.tryAcquire(g, 100L, 1L, 5, 42L, "holder-A", Duration.ofHours(1));
        mutexes.tryAcquire(g, 200L, 2L, 5, 42L, "holder-B", Duration.ofHours(1));

        Optional<MutexLockManager.Waiter> woken = mutexes.release(g, 100L);
        assertThat(woken).isPresent();

        // 第三方在唤醒间隙抢到锁
        assertThat(mutexes.tryAcquire(g, 900L, 99L, 5, 42L, "holder-intruder", Duration.ofHours(1))).isTrue();
        // 被唤醒者重新竞争失败 → 重新登记（原 seq=2）
        assertThat(mutexes.tryAcquire(g, 200L, 2L, 5, 42L, "holder-B", Duration.ofHours(1))).isFalse();
        assertThat(mutexes.waitersCount(g)).isEqualTo(1);
    }
}
