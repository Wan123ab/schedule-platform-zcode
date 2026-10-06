package com.flowops.scheduler;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * flowops-scheduler 启动类：调度主循环 + 选主 + 恢复（docs/03 §5.1）。
 * 对外不提供 REST API，只暴露 actuator（探活 / Prometheus）。
 */
@SpringBootApplication
@MapperScan("com.flowops.domain.mapper")
@EnableScheduling
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
