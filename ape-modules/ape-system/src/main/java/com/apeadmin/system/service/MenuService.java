package com.apeadmin.system.service;

import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.dto.MenuNode;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 菜单服务：树构建 + 角色可见菜单。
 */
@Service
public class MenuService {

    private final SysMenuMapper menuMapper;

    public MenuService(SysMenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /** 超管视角：全部启用且可见菜单的树。 */
    public List<MenuNode> enabledTree() {
        List<SysMenu> menus = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getStatus, 1)
                .eq(SysMenu::getVisible, 1));
        return MenuNode.buildTree(menus);
    }

    /** 指定平铺集合 → 树。 */
    public List<MenuNode> treeOf(List<SysMenu> menus) {
        return MenuNode.buildTree(menus);
    }

    public List<SysMenu> listAll() {
        return menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .orderByAsc(SysMenu::getSort));
    }
}
