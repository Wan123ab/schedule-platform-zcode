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

    /**
     * 算子试运行的执行线程池（PRD §10.6）。
     *
     * <p><b>为什么不能复用 auditExecutor</b>：审计是"失败不阻断业务"的旁路任务，
     * 它的拒绝策略是 CallerRuns（宁可让请求线程自己写库）；而试运行任务一旦被
     * CallerRuns，就会把 HTTP 请求线程占住最长 10 分钟。两者的背压语义相反，
     * 必须分开。</p>
     *
     * <p><b>为什么队列容量是 0（不排队）</b>：排队意味着"接口已受理、SSE 已建流，
     * 但命令还没开始跑"——用户会盯着一个不动的日志面板，最长等到 SSE 自身超时。
     * 同步队列让第 9 个并发请求立刻拿到 42900（前端提示"稍后重试"），
     * 语义比"静默等待"清晰得多。maxPoolSize=8 与"试运行是人工低频动作"匹配，
     * 也顺带给节点侧一个并发上限（每路试运行占一条 SSH 连接）。</p>
     */
    @Bean("dryRunExecutor")
    public Executor dryRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("dryrun-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
