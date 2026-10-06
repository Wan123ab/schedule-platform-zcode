package com.flowops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * flowops-server 启动类：REST 接口 + 横切切面 + Flyway。
 * 铁律：不依赖 flowops-scheduler（D-08，协作只走 DB 状态 + Redis 队列）。
 */
@SpringBootApplication
// 按 @Mapper 注解扫全包：domain 共享 Mapper 与 server 模块内 Mapper（如 AuthQueryMapper）都要注册；
// 不用注解过滤会把普通接口误注册为 Mapper（MyBatis-Spring 的坑）
@MapperScan(basePackages = "com.flowops", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FlowopsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowopsServerApplication.class, args);
    }
}
