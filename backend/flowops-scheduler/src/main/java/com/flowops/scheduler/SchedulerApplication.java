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
// 同 server：按 @Mapper 注解扫全包（domain + 模块内 Mapper 一网打尽）
@MapperScan(basePackages = "com.flowops", annotationClass = org.apache.ibatis.annotations.Mapper.class)
@EnableScheduling
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
