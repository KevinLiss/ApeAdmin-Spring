package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.system.domain.SysDept;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门管理（对齐 Python 版 /depts 契约）：
 *
 * <pre>
 * GET    /depts       — 平铺列表（M1 既有，保留兼容）
 * GET    /depts/tree  — 树（含 children，按 sort 升序）
 * POST   /depts       — 新增
 * PUT    /depts/{id}  — 更新
 * DELETE /depts/{id}  — 软删
 * </pre>
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

    @SaCheckPermission(PermConst.DEPT_LIST)
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<SysDept> all = deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                .orderByAsc(SysDept::getSort));
        return R.ok(assemble(all, 0L));
    }

    private List<Map<String, Object>> assemble(List<SysDept> all, Long parentId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<SysDept> children = all.stream()
                .filter(d -> parentId.equals(d.getParentId()))
                .sorted(Comparator.comparingInt(d -> d.getSort() == null ? 0 : d.getSort()))
                .toList();
        for (SysDept d : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("parent_id", d.getParentId());
            m.put("sort", d.getSort());
            m.put("leader", d.getLeader());
            m.put("phone", d.getPhone());
            m.put("status", d.getStatus());
            m.put("created_at", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
            m.put("children", assemble(all, d.getId()));
            nodes.add(m);
        }
        return nodes;
    }

    @OperateLog(action = "新增部门", module = "部门管理")
    @SaCheckPermission(PermConst.DEPT_ADD)
    @PostMapping
    public R<Object> create(@RequestBody DeptReq body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        SysDept d = new SysDept();
        d.setName(body.name());
        d.setParentId(body.parent_id() == null ? 0L : body.parent_id());
        d.setSort(body.sort() == null ? 0 : body.sort());
        d.setLeader(body.leader());
        d.setPhone(body.phone());
        d.setStatus(body.status() == null ? 1 : body.status());
        deptMapper.insert(d);
        return R.ok(Map.of("id", d.getId()), "创建成功");
    }

    @OperateLog(action = "更新部门", module = "部门管理")
    @SaCheckPermission(PermConst.DEPT_EDIT)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody DeptReq body) {
        SysDept d = deptMapper.selectById(id);
        if (d == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "部门不存在");
        }
        if (body.name() != null) {
            d.setName(body.name());
        }
        if (body.parent_id() != null) {
            d.setParentId(body.parent_id());
        }
        if (body.sort() != null) {
            d.setSort(body.sort());
        }
        if (body.leader() != null) {
            d.setLeader(body.leader());
        }
        if (body.phone() != null) {
            d.setPhone(body.phone());
        }
        if (body.status() != null) {
            d.setStatus(body.status());
        }
        deptMapper.updateById(d);
        return R.ok(null, "更新成功");
    }

    @OperateLog(action = "删除部门", module = "部门管理")
    @SaCheckPermission(PermConst.DEPT_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (deptMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "部门不存在");
        }
        deptMapper.deleteById(id);
        return R.ok(null, "删除成功");
    }

    public record DeptReq(String name, Long parent_id, Integer sort, String leader,
                          String phone, Integer status) {
    }
}
