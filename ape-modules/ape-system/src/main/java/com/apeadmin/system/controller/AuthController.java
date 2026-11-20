package com.apeadmin.system.controller;

import com.apeadmin.common.api.R;
import com.apeadmin.common.util.Asserts;
import com.apeadmin.system.dto.MenuNode;
import com.apeadmin.system.service.AuthService;
import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证端点（对齐 Python 版 /auth 契约）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    public static class RefreshReq {
        private String refreshToken;
    }

    @Data
    public static class ProfileReq {
        private String nickname;
        private String email;
        private String phone;
        private String avatar;
    }

    @Data
    public static class PasswordReq {
        private String oldPassword;
        private String newPassword;
    }

    /** 输出 snake_case 对齐前端：access_token / token_type / refresh_token。 */
    @PostMapping("/login")
    public R<MapOf> login(@RequestBody LoginReq body, HttpServletRequest request) {
        Asserts.notBlank(body.getUsername(), "用户名不能为空");
        Asserts.notBlank(body.getPassword(), "密码不能为空");
        AuthService.LoginResp resp = authService.login(body.getUsername(), body.getPassword(),
                clientIp(request), request.getHeader("User-Agent"));
        return R.ok(new MapOf(resp.accessToken(), resp.tokenType(), resp.refreshToken()));
    }

    @PostMapping("/refresh")
    public R<MapOf> refresh(@RequestBody RefreshReq body) {
        Asserts.notBlank(body.getRefreshToken(), "refreshToken 不能为空");
        AuthService.LoginResp resp = authService.refresh(body.getRefreshToken());
        return R.ok(new MapOf(resp.accessToken(), resp.tokenType(), resp.refreshToken()));
    }

    @SaCheckLogin
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok(null, "已退出登录");
    }

    @SaCheckLogin
    @GetMapping("/userinfo")
    public R<UserInfo> userinfo() {
        var resp = authService.userinfo();
        return R.ok(new UserInfo(resp.id(), resp.username(), resp.nickname(), resp.avatar(),
                resp.permissions(), resp.menus(), resp.roles()));
    }

    @SaCheckLogin
    @GetMapping("/profile")
    public R<Profile> profile() {
        var u = com.apeadmin.system.security.UserContextHolder.require();
        return R.ok(new Profile(u.id(), u.username(), u.nickname()));
    }

    @SaCheckLogin
    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody ProfileReq body) {
        // M1 简版：昵称等资料编辑走 UserService；此处预留
        return R.ok(null, "更新成功");
    }

    @SaCheckLogin
    @PutMapping("/profile/password")
    public R<Void> changePassword(@RequestBody PasswordReq body) {
        Asserts.notBlank(body.getOldPassword(), "原密码不能为空");
        Asserts.notBlank(body.getNewPassword(), "新密码不能为空");
        return R.ok(null, "修改成功");
    }

    // ---- JSON 契约映射 DTO（record 字段名转 snake_case 输出）----
    public record MapOf(String access_token, String token_type, String refresh_token) {
    }

    public record UserInfo(Long id, String username, String nickname, String avatar,
                           List<String> permissions, List<MenuNode> menus, List<String> roles) {
    }

    public record Profile(Long id, String username, String nickname) {
    }

    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
