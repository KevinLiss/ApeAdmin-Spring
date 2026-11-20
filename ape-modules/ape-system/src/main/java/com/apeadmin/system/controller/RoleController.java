package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理（M1 简版：列表；增删改 M1 后段补齐）。
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final SysRoleMapper roleMapper;

    public RoleController(SysRoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    @SaCheckPermission(PermConst.ROLE_LIST)
    @GetMapping
    public R<List<RoleResp>> list() {
        List<RoleResp> items = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .orderByAsc(SysRole::getSort)).stream()
                .map(r -> new RoleResp(r.getId(), r.getName(), r.getCode(), r.getDataScope(),
                        r.getStatus(), r.getSort()))
                .toList();
        return R.ok(items);
    }

    public record RoleResp(Long id, String name, String code, Integer dataScope,
                           Integer status, Integer sort) {
    }
}
