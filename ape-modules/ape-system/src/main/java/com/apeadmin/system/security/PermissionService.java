package com.apeadmin.system.security;

import cn.dev33.satoken.stp.StpInterface;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.apeadmin.system.mapper.SysRelationMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.apeadmin.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限服务：用户权限集 + 30s 缓存；超管通配 "*"。
 *
 * <p>同时实现 sa-token {@link StpInterface}：@SaCheckPermission/@SaCheckRole 注解
 * 经 SaManager.getStpInterface() 走到这里的权限/角色列表（未注册时 sa-token 取默认空实现，
 * 导致一律 403——实证 2026-11-20，修复注解鉴权后 403 问题）。
 */
@Service
public class PermissionService implements StpInterface {

    private static final long TTL_MS = 30_000;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> permissions, long expireAt) {
    }

    private final SysUserMapper userMapper;
    private final SysRelationMapper relationMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;

    public PermissionService(SysUserMapper userMapper, SysRelationMapper relationMapper,
                             SysMenuMapper menuMapper, SysRoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.relationMapper = relationMapper;
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
    }

    /** 用户权限集（超管返回 {"*"}）。 */
    public Set<String> permissionsOf(Long userId) {
        CacheEntry e = cache.get(userId);
        if (e != null && e.expireAt() > System.currentTimeMillis()) {
            return e.permissions();
        }
        Set<String> perms = load(userId);
        cache.put(userId, new CacheEntry(perms, System.currentTimeMillis() + TTL_MS));
        return perms;
    }

    private Set<String> load(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return Set.of();
        }
        if (user.getIsSuperAdmin() != null && user.getIsSuperAdmin() == 1) {
            return Set.of("*");
        }
        Set<String> perms = new HashSet<>();
        for (Long roleId : relationMapper.selectRoleIdsByUserId(userId)) {
            for (Long menuId : relationMapper.selectMenuIdsByRoleId(roleId)) {
                SysMenu m = menuMapper.selectById(menuId);
                if (m != null && m.getStatus() == 1 && m.getPermission() != null && !m.getPermission().isBlank()) {
                    perms.add(m.getPermission());
                }
            }
        }
        return perms;
    }

    /** 用户可见菜单（构建树用平铺列表）。 */
    public List<SysMenu> visibleMenus(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            return List.of();
        }
        if (user.getIsSuperAdmin() != null && user.getIsSuperAdmin() == 1) {
            return menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                    .eq(SysMenu::getStatus, 1).eq(SysMenu::getVisible, 1));
        }
        return relationMapper.selectMenusByUserId(userId).stream()
                .filter(m -> m.getVisible() != null && m.getVisible() == 1)
                .toList();
    }

    public void evict(Long userId) {
        cache.remove(userId);
    }

    /** 管理操作后的缓存清理（用户/角色/菜单变更时全清，30s 内最多重建一次，代价可忽略）。 */
    public void evictAll() {
        cache.clear();
    }

    // ---- sa-token StpInterface：注解鉴权入口（登录后经 UserContextHolder/权限缓存）----

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.copyOf(permissionsOf(Long.valueOf(loginId.toString())));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> codes = new ArrayList<>();
        for (Long rid : relationMapper.selectRoleIdsByUserId(userId)) {
            SysRole r = roleMapper.selectById(rid);
            if (r != null && r.getStatus() != null && r.getStatus() == 1) {
                codes.add(r.getCode());
            }
        }
        return codes;
    }

    /** 超管用户名判定辅助（对齐 Python 版语义：is_super_admin 字段为准）。 */
    public boolean hasPermission(Long userId, String permission) {
        Set<String> perms = permissionsOf(userId);
        return perms.contains("*") || perms.contains(permission);
    }

    @SuppressWarnings("unused")
    private static final String UNUSED_REF = PermConst.DASHBOARD_VIEW;
}
