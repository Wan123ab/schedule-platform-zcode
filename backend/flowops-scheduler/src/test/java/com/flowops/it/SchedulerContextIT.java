package com.flowops.it;

import com.flowops.it.support.IntegrationEnvironment;
import com.flowops.scheduler.core.RecoveryService;
import com.flowops.scheduler.core.SchedulerEngine;
import com.flowops.scheduler.core.LeaderElector;
import com.flowops.scheduler.backfill.BackfillDispatcher;
import com.flowops.scheduler.lifecycle.HeartbeatScanner;
import com.flowops.scheduler.match.NodeMatcher;
import com.flowops.scheduler.queue.ReadyQueueManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scheduler 上下文冒烟（<b>O-85</b>）。
 *
 * <p><b>为什么要有它</b>：M5 给 server 补同类测试时，第一轮就抓出三个"进程根本起不来"
 * 的问题（缺 bean、映射歧义、迁移脚本语法）。scheduler 侧<b>同样</b>没有任何关卡：
 * 104 个用例全是分层单测。而它比 server 更值得怀疑 —— O-62 已经记录过一次
 * "扫描面不全导致 SchedulerWiringConfig 从未被装配"，修完之后仍然没人验证过。</p>
 *
 * <p><b>它验什么</b>：Flyway 迁移过的库上，整个 scheduler 的 Bean 装配图能否建起来
 * —— 也就是 {@code SchedulerWiringConfig} 里那一串 {@code @Bean} 的依赖是否都齐、
 * {@code scanBasePackages} 是否覆盖了 config 与 domain。</p>
 *
 * <p><b>它不验什么</b>：不验调度逻辑（那是既有 104 个单测的职责），
 * 也不希望定时循环真的跑起来 —— 见下面静态块的说明。</p>
 */
// 必须显式给 classes：启动类在 com.flowops.scheduler 包下，而本测试在 com.flowops.it，
// Spring 只会从测试类所在包向上找 —— com.flowops.it → com.flowops，找不到它。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = com.flowops.scheduler.SchedulerApplication.class)
@ActiveProfiles("it")
@ExtendWith(IntegrationEnvironment.class)
class SchedulerContextIT {

    /**
     * 类加载期清空业务表 —— 必须比 Spring 上下文更早。
     *
     * <p>scheduler 一启动就开始干活：{@code @EnableScheduling} 下
     * {@code SchedulerEngine.tick()} 每秒一次、{@code BackfillDispatcher} 5 秒一次、
     * {@code HeartbeatScanner} 5 秒一次，{@code RecoveryService} 作为
     * {@code ApplicationRunner} 更是启动即执行。它们跑在 Spring 上下文就绪之后，
     * 而 {@code @BeforeAll} 也在那之后 —— 想让它们面对一个空库，
     * 唯一早于二者的时机就是这里的静态初始化块（JUnit 加载测试类时执行）。</p>
     *
     * <p>不这么做会怎样：夹具里的 PENDING 任务会被真的推进、真的去连 10.0.0.1 建 SSH、
     * 真的按告警规则发 webhook。测试会变慢，且留下说不清的副作用。</p>
     */
    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ignored) {
            // 驱动不在 classpath 上时什么都不做：环境闸门会让整类跳过
        }
        String url = "jdbc:postgresql://" + env("IT_DB_HOST", "127.0.0.1") + ":"
                + env("IT_DB_PORT", "55432") + "/" + env("IT_DB_NAME", "flowops_it");
        try (Connection conn = DriverManager.getConnection(url,
                env("IT_DB_USER", "flowops"), env("IT_DB_PASSWORD", "flowops"))) {
            try (var st = conn.createStatement()) {
                // 只清业务表，V4 种子的角色/权限/管理员保留（它们是装配期不查、
                // 但将来审计与鉴权用例要用到的基座数据）
                st.execute("TRUNCATE TABLE task_step_retry, task_step, task, backfill_batch, "
                        + "executor_node, queue, credential, \"trigger\", workflow_version, "
                        + "workflow_step, workflow_edge, workflow, operator_version, operator, "
                        + "project_member, project, task_log RESTART IDENTITY CASCADE");
            }
        } catch (Exception e) {
            throw new IllegalStateException("scheduler IT 预清理失败（库未迁移？）: " + url, e);
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("全部调度侧 Bean 装配完成（O-62 那类『扫描面不全』问题会在这里露出来）")
    void 关键调度组件都已装配() {
        List<Class<?>> required = List.of(
                SchedulerEngine.class,
                LeaderElector.class,
                RecoveryService.class,
                BackfillDispatcher.class,
                HeartbeatScanner.class,
                ReadyQueueManager.class,
                NodeMatcher.class,
                com.flowops.scheduler.guard.MutexLockManager.class,
                com.flowops.common.guard.ConcurrencyGuard.class,
                com.flowops.domain.service.TaskCreationService.class,
                com.flowops.common.util.IdGen.class,
                com.flowops.scheduler.log.LogIngestService.class,
                com.flowops.scheduler.core.DispatchSink.class);

        for (Class<?> type : required) {
            assertThat(context.getBeanNamesForType(type))
                    .as("%s 必须被装配（缺 bean = 调度进程起不来）", type.getSimpleName())
                    .isNotEmpty();
        }
    }

    /**
     * 把 O-86 钉住：本机与 CI 的 Redis 都没设密码，而 {@code application.yml} 的
     * {@code password: ${REDIS_PASSWORD:}} 在环境变量缺失时解析成空串。
     * Redisson 会拿空串去发 {@code AUTH} → {@code ERR Client sent AUTH, but no password is set}
     * → 建连失败 → IdGen / MutexLockManager / LeaderElector 全部装配不出来 → 进程起不来。
     */
    @Test
    @DisplayName("空 Redis 密码被当作未配置（O-86），且 Redisson 真的连得上")
    void 空密码不发鉴权() {
        org.redisson.api.RedissonClient redisson = context.getBean(org.redisson.api.RedissonClient.class);

        assertThat(redisson.getConfig().isSingleConfig()).isTrue();
        assertThat(redisson.getConfig().useSingleServer().getPassword()).isNull();
        // 连得上才算数：配置对但连不上，上面的断言照样是绿的
        assertThat(redisson.getAtomicLong("it:scheduler:probe").incrementAndGet()).isPositive();
    }

    @Test
    @DisplayName("配置项被真正读进来了（scheduler 的 tick/扫描参数来自 application.yml）")
    void 配置绑定生效() {
        assertThat(context.getEnvironment().getProperty("flowops.scheduler.instance-id"))
                .isEqualTo("it-scheduler-1");
        assertThat(context.getEnvironment().getProperty("spring.data.redis.database"))
                .isEqualTo("10");
    }
}
