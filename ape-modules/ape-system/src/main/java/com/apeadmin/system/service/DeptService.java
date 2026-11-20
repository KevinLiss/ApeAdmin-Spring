package com.apeadmin.system.service;

import com.apeadmin.system.domain.SysDept;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门服务：子树计算（数据权限依赖，量小内存递归）。
 */
@Service
public class DeptService {

    private final SysDeptMapper deptMapper;

    public DeptService(SysDeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    /** 部门及全部子部门 ID（含自身）。 */
    public Set<Long> selfAndChildIds(Long deptId) {
        Set<Long> result = new HashSet<>();
        result.add(deptId);
        List<SysDept> all = deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                .eq(SysDept::getStatus, 1));
        collectChildren(deptId, all, result);
        return result;
    }

    private void collectChildren(Long parentId, List<SysDept> all, Set<Long> acc) {
        for (SysDept d : all) {
            if (parentId.equals(d.getParentId()) && acc.add(d.getId())) {
                collectChildren(d.getId(), all, acc);
            }
        }
    }
}
