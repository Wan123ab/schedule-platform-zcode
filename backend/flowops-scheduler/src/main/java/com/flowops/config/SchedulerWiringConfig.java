package com.flowops.config;

import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.guard.DbConcurrencyGuard;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import com.flowops.domain.mapper.concurrency.CredentialQueryMapper;
import com.flowops.domain.mapper.concurrency.SchedulingQueryMapper;
import com.flowops.domain.mapper.task.RetryQueryMapper;
import com.flowops.domain.mapper.task.TaskLogMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.security.SecretCryptoService;
import com.flowops.executor.client.ExecutorClient;
import com.flowops.executor.client.SshExecutorClient;
import com.flowops.scheduler.core.CompletionBus;
import com.flowops.scheduler.core.DispatchSink;
import com.flowops.scheduler.core.ExecutorDispatchSink;
import com.flowops.scheduler.core.SchedulerTickPipeline;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.lifecycle.LifecycleScanner;
import com.flowops.scheduler.lifecycle.ResourceReleaser;
import com.flowops.scheduler.log.LogIngestService;
import com.flowops.scheduler.match.NodeMatcher;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.queue.ReadyQueueManager;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度内核装配（flowops-scheduler 专用；flowops-server 的 classpath 不含本模块，互不干扰）。
 * 组件均为无状态或单活内存态，仅允许主循环线程访问（docs/06 §3.1 单线程契约）；
 * 下发池与其回执是唯一的多线程边界，经 CompletionBus 单向回流。
 */
@Configuration
public class SchedulerWiringConfig {

    @Bean
    public ReservedLedger reservedLedger() {
        return new ReservedLedger();   // D-22：调度器内存权威副本，启动重建在 RecoveryService（M1 后续）
    }

    @Bean
    public ReadyQueueManager readyQueueManager(
            RedissonClient redisson, TaskStepMapper taskStepMapper,
            @Value("${flowops.scheduler.tick-dispatch-batch:64}") int tickDispatchBatch,
            @Value("${flowops.scheduler.scan-limit:256}") int scanLimit,
            @Value("${flowops.scheduler.head-block-threshold:10}") int headBlockThreshold) {
        return new ReadyQueueManager(redisson, taskStepMapper, tickDispatchBatch, scanLimit, headBlockThreshold);
    }

    @Bean
    public NodeMatcher nodeMatcher(ReservedLedger ledger) {
        return new NodeMatcher(ledger);
    }

    @Bean
    public MutexLockManager mutexLockManager(RedissonClient redisson) {
        return new MutexLockManager(redisson);
    }

    @Bean
    public ConcurrencyGuard concurrencyGuard(ConcurrencyQueryMapper mapper) {
        return new DbConcurrencyGuard(mapper);   // D-12：所有触发路径的唯一并发检查入口
    }

    @Bean
    public CompletionBus completionBus() {
        return new CompletionBus();   // 回执 → 主循环的单向通道（单线程契约守护者）
    }

    @Bean
    public LogIngestService logIngestService(TaskLogMapper taskLogMapper, RedissonClient redisson) {
        return new LogIngestService(taskLogMapper, redisson);   // D-10：日志双通道的采集侧
    }

    @Bean
    public ExecutorClient executorClient() {
        return new SshExecutorClient();   // Q-01：一期 SSH 为主
    }

    @Bean
    public DispatchSink dispatchSink(ExecutorClient executor, CredentialQueryMapper credentialQuery,
                                     SecretCryptoService crypto, CompletionBus bus,
                                     TaskStepMapper taskStepMapper, LogIngestService logIngest,
                                     @Value("${flowops.scheduler.dispatch-pool.core:8}") int core,
                                     @Value("${flowops.scheduler.dispatch-pool.max:32}") int max,
                                     @Value("${flowops.scheduler.dispatch-pool.queue:1000}") int queue) {
        // docs/06 §13.2：有界下发池 + 背压回退（拒绝 → 退回 WAITING_RESOURCE）
        return new ExecutorDispatchSink(executor, credentialQuery, crypto, bus, taskStepMapper,
                logIngest, core, max, queue);
    }

    @Bean
    public SchedulerTickPipeline schedulerTickPipeline(
            SchedulingQueryMapper schedulingQuery, ConcurrencyQueryMapper concurrencyQuery,
            TaskMapper taskMapper, TaskStepMapper taskStepMapper,
            ConcurrencyGuard concurrencyGuard, ReadyQueueManager readyQueue,
            NodeMatcher nodeMatcher, ReservedLedger ledger, MutexLockManager mutexes,
            DispatchSink dispatchSink, CompletionBus completionBus,
            RetryQueryMapper retryQuery, LogIngestService logIngest,
            LifecycleScanner lifecycleScanner, ResourceReleaser releaser) {
        return new SchedulerTickPipeline(schedulingQuery, concurrencyQuery, taskMapper, taskStepMapper,
                concurrencyGuard, readyQueue, nodeMatcher, ledger, mutexes, dispatchSink,
                completionBus, retryQuery, logIngest, lifecycleScanner, releaser);
    }
}
