package com.apeadmin.mcp;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * MCP 注册表（内存单例，对齐 Python 版 McpManager）：
 *
 * <p>Tools：核心或插件注册，可被 AI Agent 与 HTTP API 调用；
 * Resources：只读数据源（handler 或静态内容）；
 * Prompts：模板（{placeholder} 简单替换）。
 *
 * <p>RBAC：listTools 按用户权限过滤（requiredPermissions 任一命中即可见；
 * 空 = 登录即可见）。
 * 工具执行带超时（防插件 handler 挂起阻塞 AI 轮次/SSE/HTTP）。
 *
 * <p>持久化（sys_mcp_tool）：注册即 upsert，注销标记 disabled——重启后
 * 由 {@link McpBootstrap} 重建注册（M3 内置工具为代码常驻，插件工具 M5 起经 handler_ref 恢复）。
 */
@org.springframework.stereotype.Component
public class McpManager {

    /** 工具执行超时（秒）。 */
    public static final int TOOL_CALL_TIMEOUT_SECONDS = 30;

    /** 工具定义（handler 参数 (argumentsJson, userId) → JSON 字符串结果）。 */
    @Data
    public static class Tool {
        private final String name;
        private final String description;
        private final String title;
        private final BiFunction<String, Long, String> handler;
        private final Map<String, Object> inputSchema;
        private final List<String> requiredPermissions;
        private final String pluginName;
        private final String category;
    }

    @Data
    public static class Resource {
        private final String uri;
        private final String name;
        private final String description;
        private final String mimeType;
        private final java.util.function.Supplier<String> handler;   // null = 静态内容
        private final String staticContent;
        private final String pluginName;
    }

    @Data
    public static class Prompt {
        private final String name;
        private final String description;
        private final String template;   // {placeholder} 简单替换
        private final List<String> arguments;
        private final String pluginName;
    }

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, Prompt> prompts = new ConcurrentHashMap<>();

    /** 持久化钩子（由 McpService 注入；null 时纯内存注册）。 */
    private volatile McpPersistence persistence;

    public interface McpPersistence {
        void upsertTool(Tool t);

        void markToolDisabled(String name);
    }

    public void setPersistence(McpPersistence persistence) {
        this.persistence = persistence;
    }

    // ---------------------------------------------------------------- Tools

    public void registerTool(String name, String description, String title,
                             BiFunction<String, Long, String> handler,
                             Map<String, Object> inputSchema,
                             List<String> requiredPermissions,
                             String pluginName, String category) {
        Tool t = new Tool(name, description, title, handler, inputSchema,
                requiredPermissions == null ? List.of() : requiredPermissions,
                pluginName == null ? "" : pluginName,
                category == null || category.isBlank() ? (pluginName == null || pluginName.isBlank() ? "system" : pluginName) : category);
        tools.put(name, t);
        if (persistence != null) {
            persistence.upsertTool(t);
        }
    }

    public boolean unregisterTool(String name) {
        Tool removed = tools.remove(name);
        if (removed != null && persistence != null) {
            persistence.markToolDisabled(name);
        }
        return removed != null;
    }

    public List<String> unregisterPluginTools(String pluginName) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (e.getValue().getPluginName().equals(pluginName)) {
                names.add(e.getKey());
            }
        }
        for (String n : names) {
            unregisterTool(n);
        }
        return names;
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    /** 全部工具（或按权限过滤；超管 "*" 见全部）。 */
    public List<Tool> listTools(Set<String> userPermissions) {
        if (userPermissions == null || userPermissions.contains("*")) {
            return List.copyOf(tools.values());
        }
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (t.getRequiredPermissions().isEmpty()
                    || t.getRequiredPermissions().stream().anyMatch(userPermissions::contains)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 分类统计（name + count）。 */
    public List<Map<String, Object>> listCategories() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Tool t : tools.values()) {
            counts.merge(t.getCategory().isEmpty() ? "system" : t.getCategory(), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((k, v) -> out.add(Map.of("name", k, "count", v)));
        return out;
    }

    /** 执行工具（超时保护 + 入口 schema 校验）。返回 JSON 字符串。 */
    public String callTool(String name, String argumentsJson, Long userId) throws Exception {
        Tool t = tools.get(name);
        if (t == null) {
            throw new IllegalArgumentException("未知 MCP 工具: " + name);
        }
        // 入口 schema 校验：required 必填 + properties 基本类型（坏参数执行前拦截，
        // 对照 mcp-java-sdk 的服务端 JsonSchemaValidator 姿势）
        try {
            Map<String, Object> args = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of() : new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(argumentsJson, Map.class);
            String err = JsonSchemaValidator.validate(t.getInputSchema(), args);
            if (err != null) {
                throw new IllegalArgumentException("参数校验失败: " + err);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("参数不是合法 JSON 对象");
        }
        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> t.getHandler().apply(argumentsJson == null ? "{}" : argumentsJson, userId));
        try {
            return future.get(TOOL_CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("工具执行超时: " + name);
        }
    }

    // ---------------------------------------------------------------- Resources

    public void registerResource(String uri, String name, String description,
                                 java.util.function.Supplier<String> handler, String staticContent,
                                 String mimeType, String pluginName) {
        if (handler == null && staticContent == null) {
            throw new IllegalArgumentException("Resource 必须提供 handler 或 staticContent");
        }
        resources.put(uri, new Resource(uri, name, description,
                mimeType == null ? "text/plain" : mimeType, handler, staticContent,
                pluginName == null ? "" : pluginName));
    }

    public List<Resource> listResources() {
        return List.copyOf(resources.values());
    }

    public String readResource(String uri) {
        Resource r = resources.get(uri);
        if (r == null) {
            throw new IllegalArgumentException("未知 MCP 资源: " + uri);
        }
        if (r.getHandler() != null) {
            return r.getHandler().get();
        }
        return r.getStaticContent() == null ? "" : r.getStaticContent();
    }

    // ---------------------------------------------------------------- Prompts

    public void registerPrompt(String name, String description, String template,
                               List<String> arguments, String pluginName) {
        prompts.put(name, new Prompt(name, description, template,
                arguments == null ? List.of() : arguments, pluginName == null ? "" : pluginName));
    }

    public List<Prompt> listPrompts() {
        return List.copyOf(prompts.values());
    }

    /** 渲染模板（{placeholder} 替换）。 */
    public String renderPrompt(String name, Map<String, Object> args) {
        Prompt p = prompts.get(name);
        if (p == null) {
            throw new IllegalArgumentException("未知 MCP 提示词: " + name);
        }
        String out = p.getTemplate();
        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return out;
    }
}
