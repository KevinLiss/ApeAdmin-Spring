package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.domain.SysDept;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理。
 */
@RestController
@RequestMapping("/api/v1/depts")
public class DeptController {

    private final SysDeptMapper deptMapper;

    public DeptController(SysDeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    public record DeptResp(Long id, Long parent_id, String name, String leader, String phone,
                           Integer sort, Integer status) {
    }

    @SaCheckPermission(PermConst.DEPT_LIST)
    @GetMapping
    public R<List<DeptResp>> list() {
        List<DeptResp> items = deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                        .orderByAsc(SysDept::getSort)).stream()
                .map(d -> new DeptResp(d.getId(), d.getParentId(), d.getName(), d.getLeader(),
                        d.getPhone(), d.getSort(), d.getStatus()))
                .toList();
        return R.ok(items);
    }
}
