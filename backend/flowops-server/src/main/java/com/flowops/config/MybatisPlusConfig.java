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

        // ⚠️ 顺序不是风格问题，是正确性前提（O-75，2026-10-25 越权实跑发现）：
        // 数据权限必须排在分页**之前**。
        //
        // 原因：PaginationInnerInterceptor 拼 count 用的是它拿到的 boundSql.getSql()，
        // 而 count 语句最终由 executor.query(...) 直接执行 —— 传给它的 executor 是
        // MybatisPlusInterceptor 包裹之前的原始对象，因此 count **不会再走一遍拦截器链**。
        // 于是"拦截器排在分页之后"的那些增强，对 count 一律不生效。
        //
        // 实测后果（真实 PG + 真实 token）：PROJECT 范围用户查 /workflows，
        // records 只剩本项目的 1 条（正确），但 total 仍是 2（含他项目）——
        // 既把"平台上一共有几个你看不到的项目"这个数泄漏了出去，
        // 也让前端分页器显示"共 2 条"而第二页是空的。
        //
        // 把 DataPermission 前置后，count 是基于已注入行级条件的 SQL 生成的，
        // 绕不绕插件都不影响结果。MyBatis-Plus 官方文档亦要求"分页插件放最后"。
        //
        // D-19：数据权限行级注入（docs/07 §5.3）—— 仅 server；scheduler 必须看到全部数据
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor(
                new FlowopsDataPermissionHandler()));
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
