package com.apeadmin.system.dto;

import com.apeadmin.system.domain.SysMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 菜单树节点（对齐 Python 版 _build_menu_tree 字段契约）。
 */
public record MenuNode(Long id, String name, Long parentId, String type, String path, String component,
                       String permission, String icon, Integer sort, Integer visible, Integer status,
                       List<MenuNode> children) {

    /** 平铺菜单 → 树（parent_id 从 0 起，按 sort 升序）。 */
    public static List<MenuNode> buildTree(List<SysMenu> menus) {
        List<MenuNode> nodes = menus.stream()
                .map(m -> new MenuNode(m.getId(), m.getName(), m.getParentId(), m.getType(), m.getPath(),
                        m.getComponent(), m.getPermission(), m.getIcon(), m.getSort(), m.getVisible(),
                        m.getStatus(), new ArrayList<>()))
                .toList();
        return assemble(nodes, 0L);
    }

    private static List<MenuNode> assemble(List<MenuNode> nodes, Long parentId) {
        List<MenuNode> children = nodes.stream()
                .filter(n -> parentId.equals(n.parentId()))
                .sorted(Comparator.comparingInt(n -> n.sort() == null ? 0 : n.sort()))
                .toList();
        for (MenuNode n : children) {
            n.children().addAll(assemble(nodes, n.id()));
        }
        return children;
    }
}
