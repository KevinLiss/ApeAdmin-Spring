package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.dto.MenuNode;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.apeadmin.system.service.MenuService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单管理（对齐 Python 版 /menus 契约）：
 *
 * <pre>
 * GET    /menus       — 树（M1 既有，保留兼容）
 * GET    /menus/tree  — 树（前端契约端点）
 * POST   /menus       — 新增（父级校验：存在且非按钮）
 * PUT    /menus/{id}  — 更新（parent_id 变更时防环校验）
 * DELETE /menus/{id}  — 删除（物理删，级联子节点）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/menus")
public class MenuController {

    private final MenuService menuService;
    private final SysMenuMapper menuMapper;

    public MenuController(MenuService menuService, SysMenuMapper menuMapper) {
        this.menuService = menuService;
        this.menuMapper = menuMapper;
    }

    @SaCheckPermission(PermConst.MENU_LIST)
    @GetMapping
    public R<List<MenuNode>> tree() {
        return R.ok(MenuNode.buildTree(menuService.listAll()));
    }

    @SaCheckPermission(PermConst.MENU_LIST)
    @GetMapping("/tree")
    public R<List<MenuNode>> treeEndpoint() {
        return tree();
    }

    @OperateLog(action = "新增菜单", module = "菜单管理")
    @SaCheckPermission(PermConst.MENU_ADD)
    @PostMapping
    public R<Object> create(@RequestBody MenuReq body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        validateParent(body.parent_id() == null ? 0L : body.parent_id(), null);
        SysMenu m = new SysMenu();
        m.setName(body.name());
        m.setParentId(body.parent_id() == null ? 0L : body.parent_id());
        m.setType(body.type() == null ? "M" : body.type());
        m.setPath(body.path());
        m.setComponent(body.component());
        m.setPermission(body.permission());
        m.setIcon(body.icon());
        m.setSort(body.sort() == null ? 0 : body.sort());
        m.setVisible(body.visible() == null ? 1 : body.visible());
        m.setStatus(body.status() == null ? 1 : body.status());
        menuMapper.insert(m);
        return R.ok(java.util.Map.of("id", m.getId()), "创建成功");
    }

    @OperateLog(action = "更新菜单", module = "菜单管理")
    @SaCheckPermission(PermConst.MENU_EDIT)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody MenuReq body) {
        SysMenu m = menuMapper.selectById(id);
        if (m == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        if (body.parent_id() != null) {
            // 前端清空父级发 null/0 → 统一落根节点约定 parent_id=0
            long pid = body.parent_id() == null ? 0L : body.parent_id();
            validateParent(pid, id);
            m.setParentId(pid);
        }
        if (body.name() != null) {
            m.setName(body.name());
        }
        if (body.type() != null) {
            m.setType(body.type());
        }
        if (body.path() != null) {
            m.setPath(body.path());
        }
        if (body.component() != null) {
            m.setComponent(body.component());
        }
        if (body.permission() != null) {
            m.setPermission(body.permission());
        }
        if (body.icon() != null) {
            m.setIcon(body.icon());
        }
        if (body.sort() != null) {
            m.setSort(body.sort());
        }
        if (body.visible() != null) {
            m.setVisible(body.visible());
        }
        if (body.status() != null) {
            m.setStatus(body.status());
        }
        menuMapper.updateById(m);
        return R.ok(null, "更新成功");
    }

    @OperateLog(action = "删除菜单", module = "菜单管理")
    @SaCheckPermission(PermConst.MENU_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (menuMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        deleteCascade(id);
        return R.ok(null, "删除成功");
    }

    // ---------------- 内部 ----------------

    /** 父级校验：必须存在且类型非按钮（F）；更新时不得选择自身。 */
    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        SysMenu parent = menuMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上级菜单不存在");
        }
        if ("F".equals(parent.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按钮类型不能作为上级菜单");
        }
        if (selfId != null) {
            // 沿父链向上走，遇自身即成环
            Long cursor = parentId;
            int guard = 0;
            while (cursor != null && cursor != 0L && guard++ < 100) {
                if (cursor.equals(selfId)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "不能选择自身或子级作为上级");
                }
                SysMenu p = menuMapper.selectById(cursor);
                cursor = p == null ? null : p.getParentId();
            }
        }
    }

    /** 物理级联删除子树。 */
    private void deleteCascade(Long id) {
        List<SysMenu> children = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getParentId, id));
        for (SysMenu child : children) {
            deleteCascade(child.getId());
        }
        menuMapper.deleteById(id);
    }

    public record MenuReq(String name, Long parent_id, String type, String path, String component,
                          String permission, String icon, Integer sort, Integer visible, Integer status) {
    }
}
