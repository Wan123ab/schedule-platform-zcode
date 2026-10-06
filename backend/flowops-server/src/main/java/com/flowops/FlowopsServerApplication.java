package com.flowops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * flowops-server 启动类：REST 接口 + 横切切面 + Flyway。
 * 铁律：不依赖 flowops-scheduler（D-08，协作只走 DB 状态 + Redis 队列）。
 */
@SpringBootApplication
@MapperScan("com.flowops.domain.mapper")
public class FlowopsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowopsServerApplication.class, args);
    }
}
