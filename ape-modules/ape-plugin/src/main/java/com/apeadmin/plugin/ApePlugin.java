package com.apeadmin.plugin;

import java.util.Map;

/**
 * 插件接口（对齐 Python 版 PluginInterface，Java 化）。
 *
 * <p>三级插件体系：
 * <ul>
 * <li><b>L1 静态注册</b>：编译期插件 = Spring Bean 实现本接口，启动发现、后台启用/停用；
 * <li><b>L2 ZIP 热加载</b>：上传 ZIP（白名单校验）→ URLClassLoader 动态加载实现类
 *     （HTTP 路由不动态注册，提供 MCP 工具 / 事件订阅 / 配置面）；</li>
 * <li><b>L3 MCP 外挂</b>：独立进程的 MCP server（stdio JSON-RPC），网关代理其工具。</li>
 * </ul>
 *
 * <p>生命周期：install()（启用：建表/seed/菜单）→ registerRuntime()（挂 MCP 工具、订阅事件）
 * → unregisterRuntime()（释放）→ uninstall()（停用清理）。
 */
public interface ApePlugin {

    // ---- 元数据 ----
    String name();

    String displayName();

    String description();

    default String version() {
        return "1.0.0";
    }

    default String author() {
        return "";
    }

    /** 安装：建表 / seed 数据 / 注册菜单（幂等）。 */
    default void install() {
    }

    /** 卸载清理（停用时调用；数据默认保留，卸载语义由插件自定）。 */
    default void uninstall() {
    }

    /**
     * 注册运行时资源：MCP 工具 / 事件订阅。
     * 返回工具名列表（PluginManager 负责注销追踪）。
     */
    default java.util.List<String> registerRuntime(McpBinder mcp, EventBus bus) {
        return java.util.List.of();
    }

    /** 释放运行时资源（默认空实现；MCP 工具由 Manager 统一注销）。 */
    default void unregisterRuntime(EventBus bus) {
    }

    /** MCP 工具注册器（由框架传入，插件不直接持有 McpManager 依赖）。 */
    interface McpBinder {
        void registerTool(String toolName, String description, String title,
                         ToolHandler handler, Map<String, Object> inputSchema,
                         java.util.List<String> requiredPermissions, String category);
    }

    /** 插件工具执行入口（与 McpManager.Tool handler 签名一致）。 */
    @FunctionalInterface
    interface ToolHandler {
        String apply(String argumentsJson, Long userId) throws Exception;
    }
}
