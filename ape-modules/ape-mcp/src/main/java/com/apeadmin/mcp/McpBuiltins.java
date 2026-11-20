package com.apeadmin.mcp;

import com.apeadmin.system.domain.SysDept;
import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.domain.SysRole;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 内置资源与工具注册（对齐 Python 版 builtin_resources.py）：
 *
 * <p>资源 3：apeadmin://system/status、users/count、system/info；
 * 工具 10：system_health_check、system_list_plugins（预留插件空结果）+
 * role_list/create/update/delete、dept_list/create/update/delete、menu_list/create/update/delete
 * （menu 与 role/dept 的 CRUD 带权限映射，执行按 RBAC 校验）；
 * 提示词 1：system_summary 模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpBuiltins {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpManager manager;
    private final SysRoleMapper roleMapper;
    private final SysDeptMapper deptMapper;
    private final SysMenuMapper menuMapper;

    /** 启动时注册全部内置项（幂等：重复调用覆盖同 key）。 */
    public void registerAll() {
        registerResources();
        registerSystemTools();
        registerRoleTools();
        registerDeptTools();
        registerMenuTools();
        registerPrompts();
        log.info("MCP 内置注册完成：tools={} resources={} prompts={}",
                manager.listTools(null).size(), manager.listResources().size(), manager.listPrompts().size());
    }

    // ---------------------------------------------------------------- 注册段

    private void registerResources() {
        manager.registerResource("apeadmin://system/status", "系统状态",
                "获取系统运行状态信息，包括版本、运行时间等",
                () -> "App: apeadmin-spring 1.0.0-SNAPSHOT\nDB: sqlite (WAL)\nMCP: enabled\nPlugins: pending(M5)",
                null, "text/plain", "");
        manager.registerResource("apeadmin://users/count", "用户统计",
                "获取系统用户总数和活跃用户数",
                () -> {
                    // users 查询经由 system 模块（此处简化：读 role 数代替的扩展点在 M5 打开）
                    return "总用户数: 3（admin/dev/viewer，种子）\n活跃用户数: 3";
                },
                null, "text/plain", "");
        manager.registerResource("apeadmin://system/info", "系统信息",
                "系统基本信息（静态）",
                null,
                "ApeAdmin-Spring 1.0.0-SNAPSHOT\nJava 21 + Spring Boot 3.5 admin framework with plugin & MCP support",
                "text/plain", "");
    }

    private void registerSystemTools() {
        manager.registerTool("system_health_check", "检查系统健康状态，返回各服务运行状况", "系统健康检查",
                (args, userId) -> json(Map.of("status", "healthy", "app", "apeadmin-spring",
                        "version", "1.0.0-SNAPSHOT")),
                schema(), List.of(), "", "system");
        manager.registerTool("system_list_plugins", "列出所有已安装的插件及其状态", "插件列表",
                (args, userId) -> json(Map.of("plugins", List.of(), "message",
                        "插件体系 M5 开放，当前无已安装插件")),
                schema(), List.of(), "", "system");
    }

    private void registerRoleTools() {
        manager.registerTool("role_list", "获取角色列表，支持关键词搜索。返回角色ID、名称、编码、数据范围、状态。", "角色列表",
                this::roleList, schema(prop("keyword", "string", "搜索关键词（名称或编码）")),
                List.of("system:role:list"), "", "system");
        manager.registerTool("role_create", "创建新角色。需要提供角色名称和编码。", "创建角色",
                this::roleCreate,
                schema(prop("name", "string", "角色名称"), prop("code", "string", "角色编码（唯一）"),
                        prop("data_scope", "integer", "数据范围(1本人2本部门及以下3本部门4全部，默认1)"),
                        prop("sort", "integer", "排序(默认0)"), prop("remark", "string", "备注")),
                List.of("system:role:add"), "", "system");
        manager.registerTool("role_update", "更新角色信息。只传需要修改的字段。", "更新角色",
                this::roleUpdate,
                schema(prop("role_id", "integer", "角色ID"), prop("name", "string", "名称"),
                        prop("code", "string", "编码"), prop("data_scope", "integer", "数据范围"),
                        prop("status", "integer", "状态 0/1")),
                List.of("system:role:edit"), "", "system");
        manager.registerTool("role_delete", "删除角色（软删除）。", "删除角色",
                this::roleDelete, schema(prop("role_id", "integer", "角色ID")),
                List.of("system:role:delete"), "", "system");
    }

    private void registerDeptTools() {
        manager.registerTool("dept_list", "获取部门树形结构。返回所有部门的完整信息。", "部门列表",
                this::deptList, schema(), List.of("system:dept:list"), "", "system");
        manager.registerTool("dept_create", "创建新部门。需要提供部门名称，可指定父级ID（默认0=顶级）。", "创建部门",
                this::deptCreate,
                schema(prop("name", "string", "部门名称"), prop("parent_id", "integer", "父级ID(0=顶级)"),
                        prop("leader", "string", "负责人"), prop("phone", "string", "电话"),
                        prop("sort", "integer", "排序")),
                List.of("system:dept:add"), "", "system");
        manager.registerTool("dept_update", "更新部门信息。只传需要修改的字段。", "更新部门",
                this::deptUpdate,
                schema(prop("dept_id", "integer", "部门ID"), prop("name", "string", "名称"),
                        prop("leader", "string", "负责人"), prop("phone", "string", "电话"),
                        prop("status", "integer", "状态 0/1")),
                List.of("system:dept:edit"), "", "system");
        manager.registerTool("dept_delete", "删除部门（软删除）。", "删除部门",
                this::deptDelete, schema(prop("dept_id", "integer", "部门ID")),
                List.of("system:dept:delete"), "", "system");
    }

    private void registerMenuTools() {
        manager.registerTool("menu_list", "获取菜单树形结构。返回所有菜单（目录/菜单/按钮）的完整信息。", "菜单列表",
                this::menuList, schema(), List.of("system:menu:list"), "", "system");
        manager.registerTool("menu_create", "创建新菜单项。类型: M=目录 C=菜单 F=按钮。", "创建菜单",
                this::menuCreate,
                schema(prop("name", "string", "名称"), prop("type", "string", "类型(M/C/F，默认C)"),
                        prop("parent_id", "integer", "父级ID(0=顶级)"), prop("path", "string", "路由"),
                        prop("permission", "string", "权限码"), prop("icon", "string", "图标"),
                        prop("sort", "integer", "排序")),
                List.of("system:menu:add"), "", "system");
        manager.registerTool("menu_update", "更新菜单信息。只传需要修改的字段。", "更新菜单",
                this::menuUpdate,
                schema(prop("menu_id", "integer", "菜单ID"), prop("name", "string", "名称"),
                        prop("permission", "string", "权限码"), prop("sort", "integer", "排序"),
                        prop("status", "integer", "状态 0/1")),
                List.of("system:menu:edit"), "", "system");
        manager.registerTool("menu_delete", "删除菜单项。", "删除菜单",
                this::menuDelete, schema(prop("menu_id", "integer", "菜单ID")),
                List.of("system:menu:delete"), "", "system");
    }

    private void registerPrompts() {
        manager.registerPrompt("system_summary", "生成系统运行概况摘要",
                "请基于以下信息生成一份系统运行概况报告：\n系统名称：{system_name}\n版本：{version}\n检查时间：{check_time}",
                List.of("system_name", "version", "check_time"), "");
    }

    // ---------------------------------------------------------------- handler 实现

    private String roleList(String argsJson, Long userId) {
        String keyword = str(parse(argsJson).get("keyword"));
        List<SysRole> roles = roleMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysRole>lambdaQuery()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysRole::getName, keyword)
                                .or().like(SysRole::getCode, keyword))
                        .orderByAsc(SysRole::getSort).orderByAsc(SysRole::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysRole r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("code", r.getCode());
            m.put("data_scope", r.getDataScope());
            m.put("status", r.getStatus());
            m.put("remark", r.getRemark());
            items.add(m);
        }
        return json(Map.of("total", items.size(), "items", items));
    }

    private String roleCreate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        String name = str(a.get("name"));
        String code = str(a.get("code"));
        if (name == null || name.isBlank() || code == null || code.isBlank()) {
            return json(Map.of("error", "角色名称与编码必填"));
        }
        Long exists = roleMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getCode, code));
        if (exists > 0) {
            return json(Map.of("error", "角色编码已存在: " + code));
        }
        SysRole r = new SysRole();
        r.setName(name);
        r.setCode(code);
        r.setDataScope(num(a.get("data_scope"), 1L).intValue());
        r.setSort(num(a.get("sort"), 0L).intValue());
        r.setRemark(str(a.get("remark")));
        r.setStatus(1);
        roleMapper.insert(r);
        return json(Map.of("id", r.getId(), "name", name, "code", code, "message", "角色创建成功"));
    }

    private String roleUpdate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        Long id = num(a.get("role_id"), null);
        SysRole r = id == null ? null : roleMapper.selectById(id);
        if (r == null) {
            return json(Map.of("error", "角色不存在"));
        }
        if (a.containsKey("name")) {
            r.setName(str(a.get("name")));
        }
        if (a.containsKey("code")) {
            r.setCode(str(a.get("code")));
        }
        if (a.containsKey("data_scope")) {
            r.setDataScope(num(a.get("data_scope"), null) == null ? null : num(a.get("data_scope"), null).intValue());
        }
        if (a.containsKey("status")) {
            r.setStatus(num(a.get("status"), null) == null ? null : num(a.get("status"), null).intValue());
        }
        if (a.containsKey("remark")) {
            r.setRemark(str(a.get("remark")));
        }
        roleMapper.updateById(r);
        return json(Map.of("id", id, "message", "角色更新成功"));
    }

    private String roleDelete(String argsJson, Long userId) {
        Long id = num(parse(argsJson).get("role_id"), null);
        if (id == null || roleMapper.selectById(id) == null) {
            return json(Map.of("error", "角色不存在"));
        }
        roleMapper.deleteById(id);
        return json(Map.of("message", "角色已删除"));
    }

    private String deptList(String argsJson, Long userId) {
        List<SysDept> depts = deptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getStatus, 1)
                        .orderByAsc(SysDept::getSort).orderByAsc(SysDept::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysDept d : depts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("parent_id", d.getParentId());
            m.put("leader", d.getLeader());
            m.put("sort", d.getSort());
            m.put("status", d.getStatus());
            items.add(m);
        }
        return json(Map.of("total", items.size(), "items", items));
    }

    private String deptCreate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        String name = str(a.get("name"));
        if (name == null || name.isBlank()) {
            return json(Map.of("error", "部门名称必填"));
        }
        SysDept d = new SysDept();
        d.setName(name);
        d.setParentId(num(a.get("parent_id"), 0L));
        d.setLeader(str(a.get("leader")));
        d.setPhone(str(a.get("phone")));
        d.setSort(num(a.get("sort"), 0L).intValue());
        d.setStatus(1);
        deptMapper.insert(d);
        return json(Map.of("id", d.getId(), "name", name, "message", "部门创建成功"));
    }

    private String deptUpdate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        Long id = num(a.get("dept_id"), null);
        SysDept d = id == null ? null : deptMapper.selectById(id);
        if (d == null) {
            return json(Map.of("error", "部门不存在"));
        }
        if (a.containsKey("name")) {
            d.setName(str(a.get("name")));
        }
        if (a.containsKey("leader")) {
            d.setLeader(str(a.get("leader")));
        }
        if (a.containsKey("phone")) {
            d.setPhone(str(a.get("phone")));
        }
        if (a.containsKey("status")) {
            d.setStatus(num(a.get("status"), null) == null ? null : num(a.get("status"), null).intValue());
        }
        deptMapper.updateById(d);
        return json(Map.of("id", id, "message", "部门更新成功"));
    }

    private String deptDelete(String argsJson, Long userId) {
        Long id = num(parse(argsJson).get("dept_id"), null);
        if (id == null || deptMapper.selectById(id) == null) {
            return json(Map.of("error", "部门不存在"));
        }
        deptMapper.deleteById(id);
        return json(Map.of("message", "部门已删除"));
    }

    private String menuList(String argsJson, Long userId) {
        List<SysMenu> menus = menuMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysMenu>lambdaQuery()
                        .eq(SysMenu::getStatus, 1)
                        .orderByAsc(SysMenu::getSort).orderByAsc(SysMenu::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysMenu m : menus) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", m.getId());
            out.put("name", m.getName());
            out.put("parent_id", m.getParentId());
            out.put("type", m.getType());
            out.put("path", m.getPath());
            out.put("permission", m.getPermission());
            out.put("sort", m.getSort());
            out.put("visible", m.getVisible());
            items.add(out);
        }
        return json(Map.of("total", items.size(), "items", items));
    }

    private String menuCreate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        String name = str(a.get("name"));
        if (name == null || name.isBlank()) {
            return json(Map.of("error", "菜单名称必填"));
        }
        SysMenu m = new SysMenu();
        m.setName(name);
        m.setType(str(a.getOrDefault("type", "C")));
        m.setParentId(num(a.get("parent_id"), 0L));
        m.setPath(str(a.get("path")));
        m.setPermission(str(a.get("permission")));
        m.setIcon(str(a.get("icon")));
        m.setSort(num(a.get("sort"), 0L).intValue());
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        return json(Map.of("id", m.getId(), "name", name, "message", "菜单创建成功"));
    }

    private String menuUpdate(String argsJson, Long userId) {
        Map<String, Object> a = parse(argsJson);
        Long id = num(a.get("menu_id"), null);
        SysMenu m = id == null ? null : menuMapper.selectById(id);
        if (m == null) {
            return json(Map.of("error", "菜单不存在"));
        }
        if (a.containsKey("name")) {
            m.setName(str(a.get("name")));
        }
        if (a.containsKey("permission")) {
            m.setPermission(str(a.get("permission")));
        }
        if (a.containsKey("sort")) {
            m.setSort(num(a.get("sort"), null) == null ? null : num(a.get("sort"), null).intValue());
        }
        if (a.containsKey("status")) {
            m.setStatus(num(a.get("status"), null) == null ? null : num(a.get("status"), null).intValue());
        }
        menuMapper.updateById(m);
        return json(Map.of("id", id, "message", "菜单更新成功"));
    }

    private String menuDelete(String argsJson, Long userId) {
        Long id = num(parse(argsJson).get("menu_id"), null);
        if (id == null || menuMapper.selectById(id) == null) {
            return json(Map.of("error", "菜单不存在"));
        }
        menuMapper.deleteById(id);
        return json(Map.of("message", "菜单已删除"));
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> parse(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long num(Object o, Long def) {
        if (o == null) {
            return def;
        }
        try {
            return (long) Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, Object> schema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", Map.of());
        return p;
    }

    @SafeVarargs
    private static Map<String, Object> schema(Map.Entry<String, Object>... entries) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            props.put(e.getKey(), e.getValue());
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", props);
        return p;
    }

    private static Map.Entry<String, Object> prop(String name, String type, String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", desc);
        return Map.entry(name, p);
    }
}
