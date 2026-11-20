package com.apeadmin.system.security;

import com.apeadmin.framework.mybatis.DataScope;
import com.apeadmin.framework.mybatis.DataScopeProvider;
import com.apeadmin.system.service.DeptService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 数据范围提供者：从 JWT extra 读当前用户范围，部门子树由 DeptService 计算。
 */
@Component
public class SaUserScopeProvider implements DataScopeProvider {

    private final DeptService deptService;

    public SaUserScopeProvider(DeptService deptService) {
        this.deptService = deptService;
    }

    @Override
    public UserScope currentUserScope() {
        var user = UserContextHolder.get();
        if (user == null) {
            return null;
        }
        DataScope scope = DataScope.ofCode(UserContextHolder.currentDataScope());
        return new UserScope(user.id(), UserContextHolder.currentDeptId(), scope, user.superAdmin());
    }

    @Override
    public Set<Long> deptIdsWithChildren(Long deptId) {
        if (deptId == null) {
            return Set.of();
        }
        return deptService.selfAndChildIds(deptId);
    }
}
