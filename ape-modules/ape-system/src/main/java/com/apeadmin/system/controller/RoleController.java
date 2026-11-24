package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.mapper.SysRelationMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理（对齐 Python 版 /roles 契约）：
 *
 * <pre>
 * GET    /roles        — 分页列表 {total, page, page_size, items}
 * GET    /roles/all    — 全量启用角色 [{id,name,code}]（下拉用）
 * POST   /roles        — 新增（含 menu_ids 绑定）
 * GET    /roles/{id}   — 详情（含 menu_ids）
 * PUT    /roles/{id}   — 更新（menu_ids 可选）
 * DELETE /roles/{id}   — 软删
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final SysRoleMapper roleMapper;
    private final SysRelationMapper relationMapper;

    public RoleController(SysRoleMapper roleMapper, SysRelationMapper relationMapper) {
        this.roleMapper = roleMapper;
        this.relationMapper = relationMapper;
    }

    @SaCheckPermission(PermConst.ROLE_LIST)
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long pageSize) {
        long total = roleMapper.selectCount(null);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);  // 单页上限：防超大 pageSize 全表拉取
        List<Map<String, Object>> items = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .orderByAsc(SysRole::getSort)
                        .last("LIMIT " + safePageSize + " OFFSET " + (page - 1) * safePageSize)).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("name", r.getName());
                    m.put("code", r.getCode());
                    m.put("data_scope", r.getDataScope());
                    m.put("sort", r.getSort());
                    m.put("status", r.getStatus());
                    m.put("remark", r.getRemark());
                    m.put("created_at", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
                    return m;
                }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        return R.ok(data);
    }

    @SaCheckLogin
    @GetMapping("/all")
    public R<List<Map<String, Object>>> all() {
        List<Map<String, Object>> items = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getStatus, 1).orderByAsc(SysRole::getSort)).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("name", r.getName());
                    m.put("code", r.getCode());
                    return m;
                }).toList();
        return R.ok(items);
    }

    @OperateLog(action = "新增角色", module = "角色管理")
    @SaCheckPermission(PermConst.ROLE_ADD)
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody RoleReq body) {
        if (body.name() == null || body.name().isBlank() || body.code() == null || body.code().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name/code 必填");
        }
        Long dup = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, body.code()));
        if (dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在：" + body.code());
        }
        SysRole r = new SysRole();
        r.setName(body.name());
        r.setCode(body.code());
        r.setDataScope(body.data_scope() == null ? 1 : body.data_scope());
        r.setSort(body.sort() == null ? 0 : body.sort());
        r.setStatus(body.status() == null ? 1 : body.status());
        r.setRemark(body.remark());
        roleMapper.insert(r);
        if (body.menu_ids() != null) {
            relationMapper.deleteRoleMenus(r.getId());
            for (Long menuId : body.menu_ids()) {
                relationMapper.insertRoleMenu(r.getId(), menuId);
            }
        }
        return R.ok(Map.of("id", r.getId()), "创建成功");
    }

    @SaCheckPermission(PermConst.ROLE_LIST)
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("code", r.getCode());
        m.put("data_scope", r.getDataScope());
        m.put("sort", r.getSort());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        m.put("created_at", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("menu_ids", relationMapper.selectMenuIdsByRoleId(id));
        return R.ok(m);
    }

    @OperateLog(action = "更新角色", module = "角色管理")
    @SaCheckPermission(PermConst.ROLE_EDIT)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody RoleReq body) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        if (body.name() != null) {
            r.setName(body.name());
        }
        if (body.code() != null && !body.code().equals(r.getCode())) {
            Long dup = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getCode, body.code()).ne(SysRole::getId, id));
            if (dup > 0) {
                throw new BizException(ErrorCode.CONFLICT, "角色编码已存在：" + body.code());
            }
            r.setCode(body.code());
        }
        if (body.data_scope() != null) {
            r.setDataScope(body.data_scope());
        }
        if (body.sort() != null) {
            r.setSort(body.sort());
        }
        if (body.status() != null) {
            r.setStatus(body.status());
        }
        if (body.remark() != null) {
            r.setRemark(body.remark());
        }
        roleMapper.updateById(r);
        if (body.menu_ids() != null) {
            relationMapper.deleteRoleMenus(id);
            for (Long menuId : body.menu_ids()) {
                relationMapper.insertRoleMenu(id, menuId);
            }
        }
        return R.ok(null, "更新成功");
    }

    @OperateLog(action = "删除角色", module = "角色管理")
    @SaCheckPermission(PermConst.ROLE_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        roleMapper.deleteById(id);
        relationMapper.deleteRoleMenus(id);
        return R.ok(null, "删除成功");
    }

    public record RoleReq(String name, String code, Integer data_scope, Integer sort,
                          Integer status, String remark, List<Long> menu_ids) {
    }
}
