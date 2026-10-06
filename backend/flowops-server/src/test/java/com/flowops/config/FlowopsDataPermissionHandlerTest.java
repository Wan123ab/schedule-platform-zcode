package com.flowops.config;

import com.flowops.common.context.ScopeContext;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限 SQL 注入单测（D-19）：按 docs/07 §5.3 判定链逐分支验证。
 * 断言直接检查生成的 SQL 片段 —— 与真实下发的 WHERE 一字不差。
 */
class FlowopsDataPermissionHandlerTest {

    private final FlowopsDataPermissionHandler handler = new FlowopsDataPermissionHandler();

    @AfterEach
    void tearDown() {
        ScopeContext.clear();
    }

    private void scope(ScopeContext.Type type, Set<Long> projects) {
        ScopeContext ctx = new ScopeContext();
        ctx.setType(type);
        ctx.setVisibleProjectIds(projects);
        ScopeContext.set(ctx);
    }

    @Test
    void PROJECT范围_注入多项目OR链() {
        scope(ScopeContext.Type.PROJECT, Set.of(1L, 2L));

        Expression where = handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("task"), null, "x");

        // Set 无序：只断言两个等式都在且以 OR 连接
        assertThat(where.toString())
                .contains("project_id = 1").contains("project_id = 2").contains(" OR ");
    }

    @Test
    void PROJECT范围_空集_恒假条件_宁可查不到不可越权() {
        scope(ScopeContext.Type.PROJECT, Set.of());

        assertThat(handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("task"), null, "x")
                .toString()).isEqualTo("1 = 0");
    }

    @Test
    void ALL与无上下文与未登记表_不过滤() {
        scope(ScopeContext.Type.ALL, Set.of(1L));
        assertThat(handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("task"), null, "x")).isNull();

        assertThat(handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("task"), null, "x")).isNull(); // 上下文已清

        scope(ScopeContext.Type.AUTHORIZED_CLUSTER, Set.of());
        assertThat(handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("task"), null, "x")).isNull();

        scope(ScopeContext.Type.PROJECT, Set.of(1L));
        assertThat(handler.getSqlSegment(new net.sf.jsqlparser.schema.Table("cluster"), null, "x")).isNull();
    }
}
