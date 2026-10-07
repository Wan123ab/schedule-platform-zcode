package com.flowops.config;

import com.flowops.common.context.ScopeContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.util.Map;
import java.util.Set;

/**
 * 数据权限 SQL 注入（D-19 的 Mapper 层落点，docs/07 §5.3）。
 *
 * <p><b>机制</b>：MyBatis-Plus 的 {@code DataPermissionInterceptor(MultiDataPermissionHandler)}
 * 在查询期回调本 handler，按当前 {@link ScopeContext} 对<b>登记表</b>追加行级条件。</p>
 *
 * <p><b>两类维度</b>（同一份上下文里可能同时具备，按"表上有哪一列"二选一）：</p>
 * <ul>
 *   <li>PROJECT → {@code project_id} 列（{@code project} 表自身用主键 {@code id}）；</li>
 *   <li>AUTHORIZED_CLUSTER → {@code cluster_id} 列（{@code cluster} 表自身用主键 {@code id}）。</li>
 * </ul>
 *
 * <p><b>登记制</b>：未登记的表<b>不注入</b>（宁可少过滤也不误伤 —— 例如 job 无 project_id 的表），
 * 新增业务表时按需登记并补测试，这是"数据权限放开面可审计"的前提。</p>
 *
 * <p><b>不注入的情况</b>：① 无 ScopeContext（登录/内部调用/scheduler）；② ALL。</p>
 *
 * <p><b>⚠️ 仅 server 注册本拦截器</b>（MybatisPlusConfig 本模块）：scheduler 必须看到全部数据。</p>
 */
@Slf4j
public class FlowopsDataPermissionHandler
        implements com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler {

    /**
     * 项目维度隔离：表名 → 承载「项目归属」的列名。
     *
     * <p>{@code project} 表没有 project_id 列（它就是项目本体），归属列即主键 {@code id}。</p>
     */
    private static final Map<String, String> PROJECT_SCOPED_COLUMNS = Map.of(
            "project", "id",
            "project_member", "project_id",
            "task", "project_id",
            "workflow", "project_id",
            "workflow_version", "project_id",
            "operator", "project_id");

    /**
     * 集群维度隔离：表名 → 承载「集群归属」的列名。
     *
     * <p>{@code cluster} 表没有 cluster_id 列（它就是集群本体），归属列即主键 {@code id}。</p>
     */
    private static final Map<String, String> CLUSTER_SCOPED_COLUMNS = Map.of(
            "cluster", "id",
            "executor_node", "cluster_id",
            "queue", "cluster_id",
            "task", "cluster_id",
            "task_step", "cluster_id");

    /** 供测试与文档引用的登记表快照（放开面一眼可见）。 */
    public static Set<String> projectScopedTables() {
        return PROJECT_SCOPED_COLUMNS.keySet();
    }

    public static Set<String> clusterScopedTables() {
        return CLUSTER_SCOPED_COLUMNS.keySet();
    }

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        ScopeContext scope = ScopeContext.get();
        if (scope == null || scope.isAll()) {
            return null;   // null = 不追加条件
        }
        String name = table.getName().toLowerCase();
        if (scope.getType() == ScopeContext.Type.PROJECT) {
            String column = PROJECT_SCOPED_COLUMNS.get(name);
            return column == null ? null : filter(table, column, scope.getVisibleProjectIds());
        }
        if (scope.getType() == ScopeContext.Type.AUTHORIZED_CLUSTER) {
            String column = CLUSTER_SCOPED_COLUMNS.get(name);
            return column == null ? null : filter(table, column, scope.getVisibleClusterIds());
        }
        return null;
    }

    /**
     * {@code 表.列 = id1 OR 表.列 = id2 …}；空集 → 恒假 {@code 1 = 0}（宁可查不到，不可越权看到）。
     *
     * <p>两个细节：① 用 OR 链而非 IN，是为了不绑定 jsqlparser 特定版本的 ItemsList API；
     * ② 列名带表限定（{@code task.cluster_id}），因为 task_step JOIN task 时两侧同名列会歧义。</p>
     */
    private Expression filter(Table table, String column, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new EqualsTo(new LongValue(1), new LongValue(0));
        }
        Expression chain = null;
        for (Long id : ids) {
            EqualsTo eq = new EqualsTo(new Column(table, column), new LongValue(id));
            chain = chain == null ? eq : new OrExpression(chain, eq);
        }
        log.debug("数据权限注入 table={} column={} ids={}", table.getName(), column, ids);
        return chain;
    }
}
