package com.apeadmin.common.constant;

/**
 * 权限码常量（与 Flyway 菜单种子同源；风格对齐 Python 版：模块:资源:动作 add/edit/delete）。
 */
public final class PermConst {

    public static final String DASHBOARD_VIEW = "dashboard:view";
    // 用户
    public static final String USER_LIST = "system:user:list";
    public static final String USER_ADD = "system:user:add";
    public static final String USER_EDIT = "system:user:edit";
    public static final String USER_DELETE = "system:user:delete";
    public static final String USER_RESET_PASSWORD = "system:user:reset-password";
    // 角色
    public static final String ROLE_LIST = "system:role:list";
    public static final String ROLE_ADD = "system:role:add";
    public static final String ROLE_EDIT = "system:role:edit";
    public static final String ROLE_DELETE = "system:role:delete";
    // 菜单
    public static final String MENU_LIST = "system:menu:list";
    public static final String MENU_ADD = "system:menu:add";
    public static final String MENU_EDIT = "system:menu:edit";
    public static final String MENU_DELETE = "system:menu:delete";
    // 部门
    public static final String DEPT_LIST = "system:dept:list";
    public static final String DEPT_ADD = "system:dept:add";
    public static final String DEPT_EDIT = "system:dept:edit";
    public static final String DEPT_DELETE = "system:dept:delete";
    // 设置
    public static final String SETTING_LIST = "system:setting:list";
    public static final String SETTING_EDIT = "system:setting:edit";
    // 文件
    public static final String FILE_LIST = "system:file:list";
    public static final String FILE_UPLOAD = "system:file:upload";
    public static final String FILE_CREATE_FOLDER = "system:file:create-folder";
    public static final String FILE_RENAME = "system:file:rename";
    public static final String FILE_MOVE = "system:file:move";
    public static final String FILE_DELETE = "system:file:delete";
    public static final String FILE_DOWNLOAD = "system:file:download";
    // 日志
    public static final String LOG_LIST = "system:log:list";
    public static final String LOG_DELETE = "system:log:delete";
    // 插件
    public static final String PLUGIN_LIST = "system:plugin:list";
    public static final String PLUGIN_TOGGLE = "system:plugin:toggle";
    public static final String PLUGIN_CONFIG = "system:plugin:config";
    public static final String PLUGIN_UPLOAD = "system:plugin:upload";
    public static final String PLUGIN_DELETE = "system:plugin:delete";
    public static final String PLUGIN_RESTART = "system:plugin:restart";
    // MCP
    public static final String MCP_TOOLS_LIST = "mcp:tools:list";
    public static final String MCP_TOOLS_CALL = "mcp:tools:call";
    public static final String MCP_RESOURCES_LIST = "mcp:resources:list";
    public static final String MCP_PROMPTS_LIST = "mcp:prompts:list";
    public static final String MCP_AUDIT_LIST = "mcp:audit:list";
    // AI
    public static final String AI_CHAT = "ai:chat";
    public static final String AI_CHAT_CALL = "ai:chat:call";
    public static final String PROVIDER_LIST = "ai:provider:list";
    public static final String PROVIDER_ADD = "ai:provider:add";
    public static final String PROVIDER_EDIT = "ai:provider:edit";
    public static final String PROVIDER_DELETE = "ai:provider:delete";
    // 系统版本
    public static final String VERSION_VIEW = "system:version:view";
    public static final String VERSION_UPDATE = "system:version:update";

    private PermConst() {
    }
}
