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

    /**
     * 空白 Redis 密码一律视为"未配置"（<b>O-86</b>）。
     *
     * <p>Redisson 区分不出"没配密码"和"配了空串"：{@code application.yml} 里的
     * {@code password: ${REDIS_PASSWORD:}} 在环境变量缺失时解析成 {@code ""}，
     * Redisson 于是把它当成"要鉴权"，对着一个根本没设密码的实例发 {@code AUTH ""}，
     * 服务端回 {@code ERR Client sent AUTH, but no password is set} —— 建连失败，
     * 而 {@code IdGen}/{@code MutexLockManager}/{@code LeaderElector} 都要 Redisson，
     * 结果是<b>调度进程起不来</b>。docs/08 把 {@code REDIS_PASSWORD} 列为必填，
     * 但本地与 CI 跑的就是无密码实例，那是合法场景，不该启动即死。</p>
     *
     * <p>放在这里而不是改 yml：yml 里没法表达"缺省则整键不存在"，
     * 而 {@code RedissonAutoConfigurationCustomizer} 正是 Redisson 官方留的、
     * 在 {@code RedissonClient} 建连前改 {@code Config} 的扩展点。</p>
     */
    @Bean
    public org.redisson.spring.starter.RedissonAutoConfigurationCustomizer
            blankRedisPasswordMeansNone() {
        return config -> {
            // 只动已经确定了的模式：Config 的 getter 是 protected，能用的一对是
            // isXxxConfig() + useXxxServers()，而后者在别的模式已配置时会抛 —— 必须先判再用。
            if (config.isSingleConfig()) {
                clearBlankPassword(config.useSingleServer());
            }
            if (config.isClusterConfig()) {
                clearBlankPassword(config.useClusterServers());
            }
            if (config.isSentinelConfig()) {
                clearBlankPassword(config.useSentinelServers());
            }
        };
    }

    private static void clearBlankPassword(org.redisson.config.BaseConfig<?> cfg) {
        if (cfg.getPassword() != null && cfg.getPassword().isEmpty()) {
            cfg.setPassword(null);
        }
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

    /**
     * 发号器（scheduler 侧装配）：M4-S3 起回填投放经 {@code TaskCreationService} 建任务，
     * 与 server 共享同一 Redis key 空间发号（M-09 enqueue_seq 严格单调）。
     * 本模块 classpath 无 spring-data-redis（{@code IdGen} 的
     * {@code @Autowired StringRedisTemplate} 构造器解析不了），用 Redisson bucket 显式装配。
     */
    @Bean
    public com.flowops.common.util.IdGen idGen(RedissonClient redisson) {
        return new com.flowops.common.util.IdGen(
                (com.flowops.common.util.SequenceOps) key -> redisson.getAtomicLong(key).incrementAndGet());
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
            LifecycleScanner lifecycleScanner, ResourceReleaser releaser,
            com.flowops.domain.service.AlertEngine alertEngine) {
        return new SchedulerTickPipeline(schedulingQuery, concurrencyQuery, taskMapper, taskStepMapper,
                concurrencyGuard, readyQueue, nodeMatcher, ledger, mutexes, dispatchSink,
                completionBus, retryQuery, logIngest, lifecycleScanner, releaser, alertEngine);
    }
}
