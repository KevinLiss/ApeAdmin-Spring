package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.PageResult;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.framework.mybatis.DataPermission;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.dto.UserResp;
import com.apeadmin.system.service.UserService;
import cn.dev33.satoken.annotation.SaIgnore;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理（对齐 Python 版 /users 契约；数据权限核心验证点）。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Data
    public static class UserReq {
        private Long id;
        private String username;
        private String password;
        private String nickname;
        private String email;
        private String phone;
        private Long deptId;
        private Integer status;
        private List<Long> roleIds;
    }

    @DataPermission(userColumn = "id")
    @SaCheckPermission(PermConst.USER_LIST)
    @GetMapping
    public R<PageResult<UserResp>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long pageSize,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(userService.page(page, pageSize, keyword));
    }

    @SaCheckPermission(PermConst.USER_LIST)
    @GetMapping("/{id}")
    public R<UserResp> detail(@PathVariable Long id) {
        return R.ok(userService.detail(id));
    }

    @OperateLog(action = "新增用户", module = "用户管理")
    @SaCheckPermission(PermConst.USER_ADD)
    @PostMapping
    public R<Long> create(@RequestBody UserReq body) {
        SysUser user = new SysUser();
        user.setUsername(body.getUsername());
        user.setNickname(body.getNickname());
        user.setEmail(body.getEmail());
        user.setPhone(body.getPhone());
        user.setDeptId(body.getDeptId());
        user.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        return R.ok(userService.create(user, body.getPassword(), body.getRoleIds()), "创建成功");
    }

    @OperateLog(action = "编辑用户", module = "用户管理")
    @SaCheckPermission(PermConst.USER_EDIT)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody UserReq body) {
        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setNickname(body.getNickname());
        patch.setEmail(body.getEmail());
        patch.setPhone(body.getPhone());
        patch.setDeptId(body.getDeptId());
        patch.setStatus(body.getStatus());
        userService.update(patch, body.getRoleIds());
        return R.ok(null, "更新成功");
    }

    @OperateLog(action = "删除用户", module = "用户管理")
    @SaCheckPermission(PermConst.USER_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok(null, "删除成功");
    }

    @OperateLog(action = "重置密码", module = "用户管理")
    @SaCheckPermission(PermConst.USER_RESET_PASSWORD)
    @PutMapping("/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody PasswordBody body) {
        userService.resetPassword(id, body.getPassword());
        return R.ok(null, "重置成功");
    }

    @Data
    public static class PasswordBody {
        private String password;
    }
}
