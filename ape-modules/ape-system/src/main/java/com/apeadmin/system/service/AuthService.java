package com.apeadmin.system.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.framework.mybatis.DataScope;
import com.apeadmin.system.domain.SysLoginLog;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.dto.MenuNode;
import com.apeadmin.system.mapper.SysLoginLogMapper;
import com.apeadmin.system.mapper.SysRelationMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.apeadmin.system.mapper.SysUserMapper;
import com.apeadmin.system.security.LoginUser;
import com.apeadmin.system.security.PermissionService;
import com.apeadmin.system.security.UserContextHolder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 认证服务：双令牌（access=Sa-Token JWT Simple / refresh=独立 StpLogic 体系 7d）+ 轮换拉黑。
 */
@Service
public class AuthService {

    /** refresh 令牌独立账号体系（uuid 形态，Dao 存储，天然支持轮换/吊销）。 */
    private final StpLogic refreshLogic = new StpLogic("refresh");

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysRelationMapper relationMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final PermissionService permissionService;
    private final MenuService menuService;
    private final LoginGuardService loginGuard;

    public AuthService(SysUserMapper userMapper, SysRoleMapper roleMapper, SysRelationMapper relationMapper,
                       SysLoginLogMapper loginLogMapper,
                       org.springframework.security.crypto.password.PasswordEncoder encoder,
                       PermissionService permissionService, MenuService menuService,
                       LoginGuardService loginGuard) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.relationMapper = relationMapper;
        this.loginLogMapper = loginLogMapper;
        this.encoder = encoder;
        this.permissionService = permissionService;
        this.menuService = menuService;
        this.loginGuard = loginGuard;
    }

    /** 登录响应（Controller 层输出 snake_case：access_token/refresh_token）。 */
    public record LoginResp(String accessToken, String tokenType, String refreshToken) {
    }

    public LoginResp login(String username, String password, String ip, String userAgent) {
        loginGuard.checkLock(username, ip);
        // MP 3.5.18 手动装配下 selectOne(wrapper) 会重复拼装条件（实证 Total=0），统一 selectList 取首条
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        SysUser user = users.isEmpty() ? null : users.get(0);

        boolean ok = user != null && user.getStatus() == 1
                && encoder.matches(password, user.getPassword());
        if (!ok) {
            loginGuard.recordFailure(username, ip);
            logLogin(user, username, ip, userAgent, "failed", "用户名或密码错误");
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        loginGuard.recordSuccess(username, ip);

        String[] tokens = issueTokens(user);
        logLogin(user, username, ip, userAgent, "success", null);
        updateLastLogin(user);
        return new LoginResp(tokens[0], "bearer", tokens[1]);
    }

    public LoginResp refresh(String refreshToken) {
        Object loginId = refreshLogic.getLoginIdByToken(refreshToken);
        if (loginId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期");
        }
        Long userId = Long.valueOf(loginId.toString());
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已禁用");
        }
        // 轮换：拉黑旧 refresh → 重签一对（access extra 同步刷新）
        refreshLogic.logoutByTokenValue(refreshToken);
        String[] tokens = issueTokens(user);
        return new LoginResp(tokens[0], "bearer", tokens[1]);
    }

    public void logout() {
        LoginUser ctx = UserContextHolder.require();
        StpUtil.logout();                  // 吊销当前 access
        refreshLogic.logout(ctx.id());     // 吊销该用户全部 refresh（单端场景等价）
        permissionService.evict(ctx.id());
    }

    /** userinfo：对齐 Python 版契约 {id,username,nickname,avatar,permissions,menus,roles}。 */
    public UserInfoResp userinfo() {
        LoginUser ctx = UserContextHolder.require();
        SysUser user = userMapper.selectById(ctx.id());
        Set<String> permissions = permissionService.permissionsOf(ctx.id());
        List<MenuNode> menus = ctx.superAdmin()
                ? menuService.enabledTree()
                : menuService.treeOf(permissionService.visibleMenus(ctx.id()));
        List<String> roles = relationMapper.selectRoleIdsByUserId(ctx.id()).stream()
                .map(roleMapper::selectById)
                .filter(r -> r != null && r.getStatus() == 1)
                .map(SysRole::getCode)
                .toList();
        return new UserInfoResp(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(),
                List.copyOf(permissions), menus, roles);
    }

    /**
     * 签发双令牌，返回 {access, refresh}。
     *
     * <p>注意：不能用 StpUtil.getTokenValue() 读回——login() 会把“刚创建的 token”写入
     * request Storage 的 justCreated key（该 key 不含 loginType 前缀，全局共享），
     * 第二次 login(refresh) 会覆盖第一次的值，导致两个 getTokenValue() 都读回 refresh
     * （实证：2026-11-20 登录返回两个相同 UUID）。改用 createLoginSession 直接捕获返回值。
     */
    private String[] issueTokens(SysUser user) {
        DataScope scope = widestScope(user.getId());
        String access = StpUtil.stpLogic.createLoginSession(user.getId(), new SaLoginModel()
                .setDeviceType("PC")      // 按端区分在线用户（仪表盘/在线列表可展示设备）
                .setMaxLoginCount(10)     // 同账号同设备最多 10 个并发会话，超限挤掉最旧
                .setExtra("username", user.getUsername())
                .setExtra("nickname", user.getNickname())
                .setExtra("superAdmin", user.getIsSuperAdmin() != null && user.getIsSuperAdmin() == 1)
                .setExtra("deptId", user.getDeptId())
                .setExtra("dataScope", scope.code()));
        String refresh = refreshLogic.createLoginSession(user.getId(), new SaLoginModel()
                .setDeviceType("REFRESH")
                .setTimeout(604800));
        return new String[]{access, refresh};
    }

    private DataScope widestScope(Long userId) {
        DataScope widest = DataScope.SELF;
        for (Long rid : relationMapper.selectRoleIdsByUserId(userId)) {
            SysRole role = roleMapper.selectById(rid);
            if (role != null && role.getStatus() == 1) {
                DataScope s = DataScope.ofCode(role.getDataScope());
                if (s.ordinal() > widest.ordinal()) {
                    widest = s;
                }
            }
        }
        return widest;
    }

    private void logLogin(SysUser user, String username, String ip, String ua, String result, String reason) {
        SysLoginLog entry = new SysLoginLog();
        entry.setUserId(user == null ? null : user.getId());
        entry.setUsername(username);
        entry.setIp(ip);
        entry.setUserAgent(ua);
        entry.setResult(result);
        entry.setFailReason(reason);
        entry.setCreatedAt(LocalDateTime.now());
        loginLogMapper.insert(entry);
    }

    private void updateLastLogin(SysUser user) {
        SysUser patch = new SysUser();
        patch.setId(user.getId());
        patch.setLastLoginAt(LocalDateTime.now().toString());
        userMapper.updateById(patch);
    }

    /** userinfo 响应（对齐 Python 版字段）。 */
    public record UserInfoResp(Long id, String username, String nickname, String avatar,
                               List<String> permissions, List<MenuNode> menus, List<String> roles) {
    }
}
