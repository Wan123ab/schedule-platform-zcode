package com.flowops.config;

import com.flowops.common.context.ScopeContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;

import java.util.Set;

/**
 * 数据权限 SQL 注入（D-19 的 Mapper 层落点，docs/07 §5.3）。
 *
 * <p><b>机制</b>：MyBatis-Plus 的 {@code DataPermissionInterceptor(MultiDataPermissionHandler)}
 * 在查询期回调本 handler —— 对登记表注入 {@code project_id = ?}（多项目 OR 链）条件。
 * 语义是<b>行级不可见</b>（查询返回空），越权读单体的 40301 语义由 M2 后续在
 * service 层"查不到 → 区分不存在/越权"时补齐。</p>
 *
 * <p><b>不注入的情况</b>：① 无 ScopeContext（登录/内部调用）；② ALL；③ AUTHORIZED_CLUSTER
 * （集群维度过滤随集群域落地）；④ 未登记表（无 project_id 列的表，如 cluster）。</p>
 *
 * <p><b>⚠️ 仅 server 注册本拦截器</b>（MybatisPlusConfig 本模块）：scheduler 必须看到全部数据。</p>
 */
@Slf4j
public class FlowopsDataPermissionHandler implements com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler {

    /** 含 project_id 列、需要行级隔离的表（新增表先登记再生效，避免误伤）。 */
    private static final Set<String> PROJECT_SCOPED_TABLES = Set.of(
            "task", "workflow", "workflow_version", "operator", "project_member");

    @Override
    public Expression getSqlSegment(net.sf.jsqlparser.schema.Table table, Expression where, String mappedStatementId) {
        ScopeContext scope = ScopeContext.get();
        if (scope == null || scope.isAll() || !PROJECT_SCOPED_TABLES.contains(table.getName().toLowerCase())) {
            return null;   // null = 不追加条件
        }
        if (scope.getType() == ScopeContext.Type.PROJECT) {
            Expression filter = projectFilter(scope.getVisibleProjectIds());
            if (filter != null) {
                log.debug("数据权限注入 table={} projects={}", table.getName(), scope.getVisibleProjectIds());
            }
            return filter;
        }
        return null;
    }

    /**
     * project_id = id1 OR project_id = id2 …（空集 → 恒假 1=0，宁可查不到不可越权看到）。
     * 用 OR 链而非 IN 是为了不绑定 jsqlparser 特定版本的 ItemsList API。
     */
    private Expression projectFilter(Set<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return new EqualsTo(new LongValue(1), new LongValue(0));
        }
        Expression chain = null;
        for (Long id : projectIds) {
            EqualsTo eq = new EqualsTo(new Column("project_id"), new LongValue(id));
            chain = chain == null ? eq : new OrExpression(chain, eq);
        }
        return chain;
    }
}
