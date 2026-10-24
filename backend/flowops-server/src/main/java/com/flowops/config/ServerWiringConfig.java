package com.flowops.config;

import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.guard.DbConcurrencyGuard;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * server 侧的显式装配（O-73）。
 *
 * <p><b>为什么需要它</b>：{@link DbConcurrencyGuard} 位于 domain（D-12：server 提交与
 * scheduler 准入共用同一份并发判定），但它<b>没有</b> {@code @Component} —— 此前只有
 * scheduler 的 {@code SchedulerWiringConfig} 手工 new 了一个实例。结果是 server 侧
 * 从来就没有这个 bean：{@code TaskSubmitService} 注入 {@code ConcurrencyGuard} 时
 * 直接 {@code NoSuchBeanDefinitionException}，**进程根本起不来**。
 * 而这个问题在 M4 收官时都无人察觉 —— 因为当时一个 {@code @SpringBootTest} 都没有，
 * 648 个单测全是分层测试，谁也不起 Spring 上下文。</p>
 *
 * <p><b>为什么不给 DbConcurrencyGuard 加 {@code @Component}</b>：scheduler 已经有一个
 * 显式 {@code @Bean} 声明，两边同时装配会产生两个同类型 bean（{@code NoUniqueBean}），
 * 而 scheduler 的上下文目前无法在 CI 里验证（它要 PG + Redis + Redisson）。
 * 在"改一处要动两个进程"与"各进程自己声明"之间，选后者：两个进程各有各的 wiring
 * 配置（与两侧各有 {@code CryptoKeyChecker} 同构），且改动面只有新增文件。</p>
 */
@Configuration
public class ServerWiringConfig {

    /** D-12：提交期并发检查的唯一入口（与 scheduler 侧同一实现，不是第二套口径）。 */
    @Bean
    public ConcurrencyGuard concurrencyGuard(ConcurrencyQueryMapper mapper) {
        return new DbConcurrencyGuard(mapper);
    }
}
