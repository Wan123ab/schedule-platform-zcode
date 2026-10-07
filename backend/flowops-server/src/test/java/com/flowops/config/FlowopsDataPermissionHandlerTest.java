package com.flowops.config;

import com.flowops.common.context.ScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限 SQL 注入单测（D-19）：按 docs/07 §5.3 判定链逐分支验证。
 * 断言直接检查生成的 SQL 片段 —— 与真实下发的 WHERE 一字不差。
 *
 * <p>M2 起新增 <b>集群维度</b>（AUTHORIZED_CLUSTER → {@code cluster_id}），
 * 与项目维度（PROJECT → {@code project_id}）并列，按"表上有哪一列"二选一。</p>
 */
class FlowopsDataPermissionHandlerTest {

    private final FlowopsDataPermissionHandler handler = new FlowopsDataPermissionHandler();

    @AfterEach
    void tearDown() {
        ScopeContext.clear();
    }

    private void scope(ScopeContext.Type type, Set<Long> projects, Set<Long> clusters) {
        ScopeContext ctx = new ScopeContext();
        ctx.setType(type);
        ctx.setVisibleProjectIds(projects);
        ctx.setVisibleClusterIds(clusters);
        ScopeContext.set(ctx);
    }

    private String segment(String table) {
        Expression where = handler.getSqlSegment(new Table(table), null, "x");
        return where == null ? null : where.toString();
    }

    // ── 项目维度 ────────────────────────────────────────────

    @Test
    void PROJECT范围_注入多项目OR链() {
        scope(ScopeContext.Type.PROJECT, Set.of(1L, 2L), Set.of());

        // Set 无序：只断言两个等式都在且以 OR 连接
        assertThat(segment("task"))
                .contains("project_id = 1").contains("project_id = 2").contains(" OR ");
    }

    @Test
    void PROJECT范围_空集_恒假条件_宁可查不到不可越权() {
        scope(ScopeContext.Type.PROJECT, Set.of(), Set.of());

        assertThat(segment("task")).isEqualTo("1 = 0");
    }

    @Test
    void PROJECT范围_project表用主键id而非project_id() {
        scope(ScopeContext.Type.PROJECT, Set.of(7L), Set.of());

        // project 表没有 project_id 列（它就是项目本体）——登记表的列名映射必须正确
        assertThat(segment("project")).contains("project.id = 7");
    }

    // ── 集群维度（M2 新增）────────────────────────────────────

    @Test
    void AUTHORIZED_CLUSTER范围_集群表用主键id_其余表用cluster_id() {
        scope(ScopeContext.Type.AUTHORIZED_CLUSTER, Set.of(), Set.of(11L, 12L));

        assertThat(segment("cluster")).contains("cluster.id = 11").contains("cluster.id = 12");
        assertThat(segment("executor_node")).contains("executor_node.cluster_id = 11");
        assertThat(segment("queue")).contains("queue.cluster_id = 11");
        assertThat(segment("task")).contains("task.cluster_id = 11");
        assertThat(segment("task_step")).contains("task_step.cluster_id = 11");
    }

    @Test
    void AUTHORIZED_CLUSTER范围_无授权集群_恒假_而不是放行() {
        scope(ScopeContext.Type.AUTHORIZED_CLUSTER, Set.of(), Set.of());

        assertThat(segment("cluster")).isEqualTo("1 = 0");
        assertThat(segment("executor_node")).isEqualTo("1 = 0");
    }

    @Test
    void AUTHORIZED_CLUSTER范围_不按项目过滤_两个维度互不越界() {
        scope(ScopeContext.Type.AUTHORIZED_CLUSTER, Set.of(1L), Set.of(11L));

        // 运维范围只看集群维度：task 上注入的是 cluster_id，不能同时按 project_id 收窄
        assertThat(segment("task")).contains("cluster_id = 11").doesNotContain("project_id");
        // 未登记的集群维度列 → 不过滤（如 project 表没有集群归属）
        assertThat(segment("project")).isNull();
    }

    @Test
    void 列名带表限定_避免task_step_join_task时的同名歧义() {
        scope(ScopeContext.Type.AUTHORIZED_CLUSTER, Set.of(), Set.of(11L));

        assertThat(segment("task_step")).startsWith("task_step.cluster_id");
    }

    // ── 不过滤的情形 ────────────────────────────────────────

    @Test
    void ALL与无上下文与未登记表_不过滤() {
        scope(ScopeContext.Type.ALL, Set.of(1L), Set.of(11L));
        assertThat(segment("task")).isNull();

        ScopeContext.clear();
        assertThat(segment("task")).isNull(); // 上下文已清

        scope(ScopeContext.Type.SELF_CREATED, Set.of(1L), Set.of(11L));
        assertThat(segment("task")).isNull(); // SELF_CREATED 由 service 层细化，不在此注入

        scope(ScopeContext.Type.PROJECT, Set.of(1L), Set.of());
        assertThat(segment("cluster")).isNull(); // 集群表不在项目维度登记表里
    }
}
