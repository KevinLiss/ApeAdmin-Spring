package com.apeadmin.framework.mybatis;

import com.apeadmin.framework.mybatis.DataScopeProvider.UserScope;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;

import java.util.List;
import java.util.Set;

/**
 * 数据权限 SQL 改写器（MP MultiDataPermissionHandler，按表逐个附加条件）。
 *
 * <p>规则（RVP 模式简化）：scope 取用户多角色的最宽值；超管/无登录上下文直通；
 * 仅对受控表生效（默认 sys_user，可扩展 sys_chat_session 等）；
 * scope 无法落地时兜底恒假 1=0（绝不静默放权）。
 *
 * <p>DataScopeProvider 以 ObjectProvider 延迟解析——避免
 * SqlSessionFactory → 拦截器 → Provider → 业务 Service → Mapper → SqlSessionFactory 的创建期循环。
 */
public class ApeDataPermissionHandler implements com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler {

    /** 参与数据权限改写的表（列名由注解声明）。 */
    private final Set<String> controlledTables;
    private final org.springframework.beans.factory.ObjectProvider<DataScopeProvider> provider;

    public ApeDataPermissionHandler(org.springframework.beans.factory.ObjectProvider<DataScopeProvider> provider) {
        this(provider, Set.of("sys_user"));
    }

    public ApeDataPermissionHandler(org.springframework.beans.factory.ObjectProvider<DataScopeProvider> provider,
                                    Set<String> controlledTables) {
        this.provider = provider;
        this.controlledTables = controlledTables;
    }

    /**
     * 契约（MP MultiDataPermissionHandler）：返回「附加条件」由 MP 负责与原 where AND；
     * 返回 null 表示本表不加条件。<b>不可把原 where 拼进返回值</b>（会导致条件重复 + 参数错位）。
     */
    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        DataPermission anno = DataPermissionContextHolder.get();
        if (anno == null || !anno.enable()) {
            return null;
        }
        DataScopeProvider scopeProvider = provider.getIfAvailable();
        if (scopeProvider == null) {
            return null;
        }
        UserScope scope = scopeProvider.currentUserScope();
        if (scope == null || scope.superAdmin()) {
            return null;
        }
        String tableName = table.getName() == null ? "" : table.getName().toLowerCase();
        if (!controlledTables.contains(tableName)) {
            return null;
        }
        return buildCondition(table, anno, scope, scopeProvider);
    }

    private Expression buildCondition(Table table, DataPermission anno, UserScope scope, DataScopeProvider scopeProvider) {
        Column deptCol = new Column(table, anno.deptColumn());
        Column userCol = new Column(table, anno.userColumn());
        return switch (scope.widestScope()) {
            case ALL -> null;
            case DEPT_AND_CHILD -> inCondition(deptCol, scopeProvider.deptIdsWithChildren(scope.deptId()));
            case DEPT -> inCondition(deptCol, scope.deptId() == null ? Set.of() : Set.of(scope.deptId()));
            case SELF -> userEq(userCol, scope.userId());
        };
    }

    private Expression inCondition(Column column, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return alwaysFalse();
        }
        InExpression in = new InExpression();
        in.setLeftExpression(column);
        in.setRightExpression(new ExpressionList(ids.stream().map(LongValue::new).toList()));
        return in;
    }

    private Expression userEq(Column column, Long userId) {
        if (userId == null) {
            return alwaysFalse();
        }
        return new EqualsTo(column, new LongValue(userId));
    }

    private Expression alwaysFalse() {
        return new EqualsTo(new LongValue(1), new LongValue(0));
    }
}
