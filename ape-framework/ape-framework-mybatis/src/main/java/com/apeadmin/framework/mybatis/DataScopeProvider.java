package com.apeadmin.framework.mybatis;

import java.util.Set;

/**
 * 用户数据范围提供者（framework 定义接口，ape-system 实现）。
 * framework 层不依赖业务模块，部门子树由实现方计算。
 */
public interface DataScopeProvider {

    /**
     * 当前用户范围。返回 null 表示上下文无用户（系统内部调用），不参与数据权限过滤。
     */
    record UserScope(Long userId, Long deptId, DataScope widestScope, boolean superAdmin) {
    }

    UserScope currentUserScope();

    /**
     * 部门及全部子部门 ID 集合（含自身）。实现方需处理部门树缓存。
     */
    Set<Long> deptIdsWithChildren(Long deptId);
}
