package com.apeadmin.system.service;

import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.PageResult;
import com.apeadmin.framework.mybatis.DataPermission;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.dto.UserResp;
import com.apeadmin.system.mapper.SysRelationMapper;
import com.apeadmin.system.security.PermissionService;
import com.apeadmin.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户服务：分页（数据权限生效点）/ CRUD / 重置密码 / 归属校验（防越权收口）。
 */
@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRelationMapper relationMapper;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final PermissionService permissionService;

    public UserService(SysUserMapper userMapper, SysRelationMapper relationMapper,
                       org.springframework.security.crypto.password.PasswordEncoder encoder,
                       PermissionService permissionService) {
        this.userMapper = userMapper;
        this.relationMapper = relationMapper;
        this.encoder = encoder;
        this.permissionService = permissionService;
    }

    /** 分页列表——数据权限核心验证点（@DataPermission 注解 → MP 拦截器改写 SQL）；sys_user 属主列为主键 id。 */
    @DataPermission(userColumn = "id")
    public PageResult<UserResp> page(long page, long pageSize, String keyword) {
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<SysUser>lambdaQuery()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysUser::getUsername, keyword)
                                .or().like(SysUser::getNickname, keyword))
                        .orderByDesc(SysUser::getId));
        List<UserResp> items = p.getRecords().stream().map(this::toResp).toList();
        return PageResult.of(p.getTotal(), items);
    }

    public UserResp detail(Long id) {
        SysUser user = require(id);
        return toResp(user);
    }

    public Long create(SysUser user, String password, List<Long> roleIds) {
        Long exists = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, user.getUsername()));
        if (exists != null && exists > 0) {
            throw BizException.conflict("用户名已存在");
        }
        if (password == null || password.length() < 6) {
            throw BizException.badRequest("密码至少 6 位");
        }
        user.setPassword(encoder.encode(password));
        userMapper.insert(user);
        if (roleIds != null) {
            roleIds.forEach(rid -> relationMapper.insertUserRole(user.getId(), rid));
        }
        return user.getId();
    }

    public void update(SysUser patch, List<Long> roleIds) {
        SysUser exists = require(patch.getId());
        patch.setUsername(null);        // 用户名不可改
        patch.setPassword(null);        // 密码走重置
        patch.setIsSuperAdmin(null);    // 超管标记不可通过本接口改
        userMapper.updateById(patch);
        if (roleIds != null) {
            relationMapper.deleteUserRoles(patch.getId());
            roleIds.forEach(rid -> relationMapper.insertUserRole(patch.getId(), rid));
        }
        permissionService.evict(exists.getId());
    }

    public void delete(Long id) {
        SysUser user = require(id);
        if (user.getIsSuperAdmin() != null && user.getIsSuperAdmin() == 1) {
            throw BizException.forbidden("超级管理员不可删除");
        }
        userMapper.deleteById(id);
        relationMapper.deleteUserRoles(id);
        permissionService.evict(id);
    }

    public void resetPassword(Long id, String newPassword) {
        SysUser user = require(id);
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.badRequest("密码至少 6 位");
        }
        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setPassword(encoder.encode(newPassword));
        userMapper.updateById(patch);
        permissionService.evict(id);
    }

    /** 归属校验收口：非超管只能操作自己可见范围的资源（SmartAdmin 防越权纪律）。 */
    public void assertAccessible(Long targetId, LoginUserActor actor) {
        if (actor.superAdmin() || actor.id().equals(targetId)) {
            return;
        }
        throw BizException.forbidden("无权限操作该用户");
    }

    public record LoginUserActor(Long id, boolean superAdmin) {
    }

    private SysUser require(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    private UserResp toResp(SysUser user) {
        List<Long> roleIds = relationMapper.selectRoleIdsByUserId(user.getId());
        return new UserResp(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhone(), user.getAvatar(), user.getDeptId(), user.getIsSuperAdmin(),
                user.getStatus(), user.getLastLoginAt(), user.getCreatedAt(), roleIds);
    }
}
