package com.flowops.config;

import com.flowops.executor.client.ExecutorClient;
import com.flowops.executor.client.SshExecutorClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行客户端装配（docs/03 §1.1）。
 *
 * <p><b>为什么在 server 里注册 bean</b>：{@code SshExecutorClient} 是纯协议适配类
 * （无 Spring 依赖），需要在 server 侧用于「节点连通性测试」这一个动作；
 * scheduler 侧由 {@code SchedulerWiringConfig} 自行装配，两者互不共享实例，
 * 因为它们的职责与生命周期不同（server 请求级短连接 vs scheduler 常驻派发）。</p>
 */
@Configuration
public class ExecutorClientConfig {

    @Bean
    public ExecutorClient executorClient() {
        return new SshExecutorClient();
    }
}
