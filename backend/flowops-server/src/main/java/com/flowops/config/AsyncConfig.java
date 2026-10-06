package com.flowops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 线程模型（docs/03 §6.1）：
 * REST 请求走虚拟线程（application.yml: spring.threads.virtual.enabled=true）；
 * 审计异步落库走独立有界线程池 —— 审计失败不阻断业务，但审计缺失是合规风险，
 * 拒绝策略用 CallerRunsPolicy 保证最终写入。
 */
@Configuration
public class AsyncConfig {

    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
