package com.apeadmin.chat.agent;

import com.apeadmin.common.constant.PermConst;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apeadmin.mcp.McpManager;
import com.apeadmin.mcp.McpService;
import com.apeadmin.system.domain.SysDept;
import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.apeadmin.system.mapper.SysRelationMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.apeadmin.system.mapper.SysUserMapper;
import com.apeadmin.system.security.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统工具注册表（6 工具，对齐 Python 版 SYSTEM_TOOLS）。
 *
 * <p>每个工具暴露给 LLM 作 function-calling；执行时按当前用户 RBAC 权限校验
 * （工具名 → 所需权限映射）。返回值为 JSON 字符串（LLM tool 消息 content）。
 *
 * <p>M3：合并 MCP 注册表工具——定义合并进 toolDefinitions，执行回退到 McpManager
 * （需 mcp:tools:call 权限，AI 触发的调用同路写审计）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysDeptMapper deptMapper;
    private final SysMenuMapper menuMapper;
    private final SysRelationMapper relationMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder encoder;
    private final McpManager mcpManager;   // 可选依赖：M3 合并 MCP 工具
    private final McpService mcpService;

    /** LLM function-calling 工具定义（OpenAI 兼容格式）。 */
    public List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(tool("get_user_list", "获取系统用户列表（分页）。返回用户ID、用户名、昵称、邮箱、手机、状态、角色信息。",
                props(
                        prop("page", "integer", "页码，默认1"),
                        prop("page_size", "integer", "每页数量，默认10"),
                        prop("keyword", "string", "搜索关键词（用户名或昵称）")),
                null));
        defs.add(tool("get_user_detail", "获取单个用户的详细信息，包括角色和部门。",
                props(prop("user_id", "integer", "用户ID")),
                List.of("user_id")));
        defs.add(tool("create_user", "创建新用户。需要提供用户名和密码。",
                props(
                        prop("username", "string", "用户名（唯一）"),
                        prop("nickname", "string", "昵称"),
                        prop("password", "string", "密码（至少6位）"),
                        prop("email", "string", "邮箱"),
                        prop("phone", "string", "手机号")),
                List.of("username", "password")));
        defs.add(tool("update_user", "更新用户信息。只传需要修改的字段。",
                props(
                        prop("user_id", "integer", "用户ID"),
                        prop("nickname", "string", "昵称"),
                        prop("email", "string", "邮箱"),
                        prop("phone", "string", "手机号"),
                        prop("status", "integer", "状态: 0=禁用 1=启用")),
                List.of("user_id")));
        defs.add(tool("delete_user", "删除用户（软删除）。",
                props(prop("user_id", "integer", "用户ID")),
                List.of("user_id")));
        defs.add(tool("get_system_stats", "获取系统统计信息：用户总数、角色总数、菜单总数、部门总数。",
                props(),
                null));
        // M3：合并 MCP 注册表工具（AI 可调用 role/dept/menu CRUD、system_health_check 等）
        try {
            for (McpManager.Tool t : mcpManager.listTools(null)) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.getName());
                fn.put("description", t.getDescription() == null || t.getDescription().isBlank()
                        ? "MCP 工具: " + t.getName() : t.getDescription());
                fn.put("parameters", t.getInputSchema() == null || t.getInputSchema().isEmpty()
                        ? Map.of("type", "object", "properties", Map.of()) : t.getInputSchema());
                defs.add(Map.of("type", "function", "function", fn));
            }
        } catch (Exception e) {
            log.warn("MCP 工具合并失败（降级为纯系统工具）: {}", e.getMessage());
        }
        return defs;
    }

    /** 工具名 → 所需权限（空 = 登录即可）。 */
    private static final Map<String, List<String>> PERMISSIONS = Map.of(
            "get_user_list", List.of(PermConst.USER_LIST),
            "get_user_detail", List.of(PermConst.USER_LIST),
            "create_user", List.of(PermConst.USER_ADD),
            "update_user", List.of(PermConst.USER_EDIT),
            "delete_user", List.of(PermConst.USER_DELETE),
            "get_system_stats", List.of());

    /** 权限校验（超管 "*" 通配；MCP 工具由 requiredPermissions 判定）。 */
    public boolean checkPermission(String toolName, Long userId) {
        Set<String> perms = permissionService.permissionsOf(userId);
        for (String need : PERMISSIONS.getOrDefault(toolName, List.of())) {
            if (!perms.contains("*") && !perms.contains(need)) {
                return false;
            }
        }
        // MCP 工具：需 mcp:tools:call 且工具自身 requiredPermissions 任一命中
        McpManager.Tool mcpTool = mcpManager.getTool(toolName);
        if (mcpTool != null) {
            if (!perms.contains("*") && !perms.contains(PermConst.MCP_TOOLS_CALL)) {
                return false;
            }
            if (!mcpTool.getRequiredPermissions().isEmpty()) {
                boolean any = perms.contains("*")
                        || mcpTool.getRequiredPermissions().stream().anyMatch(perms::contains);
                if (!any) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 执行工具，返回 JSON 字符串（LLM tool 消息 content）。 */
    @SuppressWarnings("unchecked")
    public String execute(String toolName, String argumentsJson, Long userId) {
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                args = JSON.readValue(argumentsJson, Map.class);
            }
        } catch (Exception ignore) {
            // 参数解析失败按空参执行
        }
        try {
            Object result = switch (toolName) {
                case "get_user_list" -> getUserList(userId, args);
                case "get_user_detail" -> getUserDetail(userId, num(args.get("user_id")));
                case "create_user" -> createUser(userId, args);
                case "update_user" -> updateUser(userId, num(args.get("user_id")), args);
                case "delete_user" -> deleteUser(userId, num(args.get("user_id")));
                case "get_system_stats" -> getSystemStats();
                default -> executeMcp(toolName, argumentsJson, userId);   // M3 回退：MCP 注册表
            };
            return result instanceof String s ? s : JSON.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", toolName, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "工具执行失败: " + e.getMessage());
            try {
                return JSON.writeValueAsString(err);
            } catch (Exception ignore) {
                return "{\"error\":\"工具执行失败\"}";
            }
        }
    }

    /** MCP 工具执行（同路写审计）。返回 JSON 字符串。 */
    private String executeMcp(String toolName, String argumentsJson, Long userId) {
        McpManager.Tool t = mcpManager.getTool(toolName);
        if (t == null) {
            return "{\"error\":\"未知工具: " + toolName + "\"}";
        }
        String username = null;
        try {
            username = com.apeadmin.system.security.UserContextHolder.peekUsername();
        } catch (Exception ignore) {
            // 后台 worker 线程无上下文
        }
        try {
            String result = mcpManager.callTool(toolName, argumentsJson, userId);
            mcpService.writeAudit("tool", toolName, userId, username, null, result, "ok");
            return result;
        } catch (Exception e) {
            mcpService.writeAudit("tool", toolName, userId, username, null, null, "failed");
            return "{\"error\":\"MCP 工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    // ---------------------------------------------------------------- 工具实现

    private Object getUserList(Long userId, Map<String, Object> args) {
        long page = args.get("page") == null ? 1 : ((Number) args.get("page")).longValue();
        long size = args.get("page_size") == null ? 10 : Math.min(((Number) args.get("page_size")).longValue(), 50);
        String keyword = str(args.get("keyword"));
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getNickname, keyword))
                .orderByDesc(SysUser::getId));
        int total = users.size();
        int from = (int) Math.min((page - 1) * size, users.size());
        int to = (int) Math.min(from + size, users.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysUser u : users.subList(from, to)) {
            items.add(userSummary(u));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", size);
        out.put("items", items);
        return out;
    }

    private Object getUserDetail(Long userId, Long targetId) {
        SysUser u = targetId == null ? null : userMapper.selectById(targetId);
        if (u == null) {
            return Map.of("error", "用户不存在");
        }
        Map<String, Object> out = userSummary(u);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Long rid : relationMapper.selectRoleIdsByUserId(u.getId())) {
            SysRole r = roleMapper.selectById(rid);
            if (r != null && r.getStatus() == 1) {
                roles.add(Map.of("id", r.getId(), "name", r.getName(), "code", r.getCode()));
            }
        }
        out.put("roles", roles);
        if (u.getDeptId() != null) {
            SysDept d = deptMapper.selectById(u.getDeptId());
            if (d != null) {
                out.put("dept", Map.of("id", d.getId(), "name", d.getName()));
            }
        }
        out.put("is_super_admin", u.getIsSuperAdmin() != null && u.getIsSuperAdmin() == 1);
        return out;
    }

    private Object createUser(Long userId, Map<String, Object> args) {
        String username = str(args.get("username"));
        String password = str(args.get("password"));
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            return Map.of("error", "用户名与密码（≥6位）必填");
        }
        Long exists = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username));
        if (exists > 0) {
            return Map.of("error", "用户名已存在: " + username);
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setNickname(str(args.get("nickname")));
        u.setDeptId(1L);
        u.setStatus(1);
        userMapper.insert(u);
        return Map.of("id", u.getId(), "username", username, "message", "创建成功");
    }

    private Object updateUser(Long userId, Long targetId, Map<String, Object> args) {
        SysUser u = targetId == null ? null : userMapper.selectById(targetId);
        if (u == null) {
            return Map.of("error", "用户不存在");
        }
        SysUser patch = new SysUser();
        patch.setId(targetId);
        if (args.containsKey("nickname")) {
            patch.setNickname(str(args.get("nickname")));
        }
        if (args.containsKey("status")) {
            patch.setStatus(num(args.get("status")) == null ? null : num(args.get("status")).intValue());
        }
        userMapper.updateById(patch);
        permissionService.evictAll();
        return Map.of("id", targetId, "message", "更新成功");
    }

    private Object deleteUser(Long userId, Long targetId) {
        if (targetId == null || userMapper.selectById(targetId) == null) {
            return Map.of("error", "用户不存在");
        }
        if (targetId.equals(userId)) {
            return Map.of("error", "不能删除自己");
        }
        userMapper.deleteById(targetId);
        permissionService.evictAll();
        return Map.of("id", targetId, "message", "删除成功");
    }

    private Object getSystemStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", userMapper.selectCount(null));
        out.put("roles", roleMapper.selectCount(null));
        out.put("menus", menuMapper.selectCount(null));
        out.put("depts", deptMapper.selectCount(null));
        out.put("sessions", 0);
        return out;
    }

    private Map<String, Object> userSummary(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("status", u.getStatus());
        return m;
    }

    // ---------------------------------------------------------------- schema 构建

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> properties, List<String> required) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            params.put("required", required);
        }
        fn.put("parameters", params);
        return Map.of("type", "function", "function", fn);
    }

    @SafeVarargs
    private static Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    private static Map.Entry<String, Object> prop(String name, String type, String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", desc);
        return Map.entry(name, p);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long num(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
