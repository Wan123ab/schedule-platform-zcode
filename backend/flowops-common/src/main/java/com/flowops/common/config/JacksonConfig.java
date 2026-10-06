package com.flowops.common.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 全局 Jackson 配置（D-05，docs/07 §3.1）：
 * Java camelCase ⇄ JSON snake_case，一处生效，禁止局部 Converter。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
