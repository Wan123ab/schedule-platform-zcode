package com.flowops.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus 配置（docs/03 §3.4）：
 * 分页插件 + 乐观锁（version 列 CAS，调度器与人工操作并发保护）+ 公共列填充。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /** created_at / updated_at / created_by / updated_by 自动填充。 */
    @Component
    public static class AuditMetaHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, OffsetDateTime.now());
            strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
            strictInsertFill(metaObject, "createdBy", String.class, currentOperator());
            strictInsertFill(metaObject, "updatedBy", String.class, currentOperator());
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
            strictUpdateFill(metaObject, "updatedBy", String.class, currentOperator());
        }

        private String currentOperator() {
            var ctx = com.flowops.common.context.UserContext.get();
            return ctx != null ? ctx.getUsername() : "system";
        }
    }
}
