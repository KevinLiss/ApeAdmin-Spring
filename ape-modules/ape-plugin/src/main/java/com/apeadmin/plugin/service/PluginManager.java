package com.apeadmin.plugin.service;

import com.apeadmin.mcp.McpManager;
import com.apeadmin.plugin.ApePlugin;
import com.apeadmin.plugin.EventBus;
import com.apeadmin.plugin.domain.PluginDO;
import com.apeadmin.plugin.l3.StdioMcpClient;
import com.apeadmin.plugin.loader.ZipPluginLoader;
import com.apeadmin.plugin.mapper.PluginMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器：三级插件统一生命周期 + sys_plugin 持久化。
 *
 * <p>启动装配顺序：L1 Spring Bean 发现 → L2 plugins/ 目录恢复 → enabled 行统一装配
 * （L3 外挂按 config 拉起子进程）。运行期 toggle：停用注销 MCP 工具/事件；启用反向。
 *
 * <p>L3 外挂不加载 Java 类：config={"command","args","env"}，启用时 StdioMcpClient 拉起子进程，
 * 其 tools/list 结果以 "plugin:tool" 注册为代理工具（调用走 tools/call 代理）。
 */
@Slf4j
@Service
public class PluginManager implements ApplicationRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpManager mcp;
    private final EventBus bus;
    private final PluginMapper pluginMapper;
    private final ZipPluginLoader zipLoader;
    private final StdioMcpClient l3Client;
    private final ApplicationContext ctx;

    public PluginManager(McpManager mcp, EventBus bus, PluginMapper pluginMapper,
                         ZipPluginLoader zipLoader, StdioMcpClient l3Client,
                         ApplicationContext ctx) {
        this.mcp = mcp;
        this.bus = bus;
        this.pluginMapper = pluginMapper;
        this.zipLoader = zipLoader;
        this.l3Client = l3Client;
        this.ctx = ctx;
    }

    // 运行时状态
    private final Map<String, ApePlugin> instances = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ownedTools = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, String> runtimeStates = new ConcurrentHashMap<>();

    @Value("${ape.plugin.dir:./plugins}")
    private String pluginDir;

    // ---------------------------------------------------------------- 启动

    @Override
    public void run(ApplicationArguments args) {
        // L1：Spring Bean 发现（同模块与其他模块的 ApePlugin Bean）
        for (ApePlugin p : ctx.getBeansOfType(ApePlugin.class).values()) {
            instances.put(p.name(), p);
            upsertRow(p, "L1", p.getClass().getName());
        }
        // L2：plugins/ 目录恢复
        try {
            Path dir = Path.of(pluginDir);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    for (Path sub : stream.filter(Files::isDirectory).toList()) {
                        if (Files.exists(sub.resolve("plugin.json"))) {
                            try {
                                ApePlugin p = zipLoader.loadFromDirectory(sub);
                                instances.put(p.name(), p);
                                upsertRow(p, "L2", sub.toString());
                            } catch (Exception e) {
                                log.warn("L2 插件恢复失败 {}: {}", sub.getFileName(), e.getMessage());
                                lastErrors.put(sub.getFileName().toString(), e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("L2 插件目录扫描失败: {}", e.getMessage());
        }
        // enabled 行统一装配（含 L3 外挂拉起）
        for (PluginDO row : pluginMapper.selectList(Wrappers.<PluginDO>lambdaQuery()
                .eq(PluginDO::getEnabled, 1))) {
            try {
                enable(row.getName());
            } catch (Exception e) {
                log.error("插件 {} 启用失败: {}", row.getName(), e.getMessage());
            }
        }
        bus.emit(EventBus.APP_STARTUP, Map.of("plugins", instances.size()));
        log.info("插件体系就绪：L1/L2 实例 {} 个（enabled 行已装配）", instances.size());
    }

    // ---------------------------------------------------------------- 启用/停用

    /** 启用：install + registerRuntime（L3 外挂拉起子进程）。返回操作摘要。 */
    public synchronized Map<String, Object> enable(String name) throws Exception {
        PluginDO row = rowOf(name);
        boolean isL3 = "L3".equals(row.getLevel());
        if (isL3) {
            startL3(row);
            row.setEnabled(1);
            pluginMapper.updateById(row);
            runtimeStates.put(name, "active");
            return Map.of("name", name, "level", "L3", "tools", ownedTools.getOrDefault(name, List.of()));
        }
        ApePlugin p = instances.get(name);
        if (p == null) {
            throw new IllegalArgumentException("插件未注册: " + name);
        }
        p.install();
        List<String> tools = p.registerRuntime(mcpBinder(name), bus);
        ownedTools.put(name, tools);
        row.setEnabled(1);
        pluginMapper.updateById(row);
        runtimeStates.put(name, "active");
        lastErrors.remove(name);
        log.info("插件启用: {}（{} 个 MCP 工具）", name, tools.size());
        return Map.of("name", name, "level", row.getLevel(), "tools", tools);
    }

    /** 停用：注销 MCP 工具/事件 + uninstall。 */
    public synchronized Map<String, Object> disable(String name) throws Exception {
        PluginDO row = rowOf(name);
        if ("L3".equals(row.getLevel())) {
            l3Client.stop(name);
            List<String> tools = ownedTools.remove(name);
            if (tools != null) {
                for (String t : tools) {
                    mcp.unregisterTool(t);
                }
            }
        } else {
            ApePlugin p = instances.get(name);
            List<String> tools = ownedTools.remove(name);
            if (tools != null) {
                for (String t : tools) {
                    mcp.unregisterTool(t);
                }
            }
            if (p != null) {
                p.unregisterRuntime(bus);
                p.uninstall();
            }
        }
        bus.offPlugin(name);
        row.setEnabled(0);
        pluginMapper.updateById(row);
        runtimeStates.put(name, "inactive");
        log.info("插件停用: {}", name);
        return Map.of("name", name, "level", row.getLevel());
    }

    // ---------------------------------------------------------------- L2 ZIP 安装

    // ---------------------------------------------------------------- L3 外挂注册

    /** 注册 L3 外挂行（无 jar；config={"command","args","env"}）。 */
    public synchronized Map<String, Object> registerL3(String name, String displayName,
                                                        String description, Object config) {
        if (name == null || !name.matches("[a-z][a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("外挂名非法（snake_case）: " + name);
        }
        if (pluginMapper.selectCount(Wrappers.<PluginDO>lambdaQuery()
                .eq(PluginDO::getName, name)) > 0) {
            throw new IllegalArgumentException("插件已存在: " + name);
        }
        PluginDO row = new PluginDO();
        row.setName(name);
        row.setTitle(displayName == null || displayName.isBlank() ? name : displayName);
        row.setVersion("external");
        row.setLevel("L3");
        row.setModulePath("stdio-mcp");
        row.setDescription(description == null ? "MCP 外挂（stdio JSON-RPC）" : description);
        row.setEnabled(0);
        row.setConfig(serializeConfig(config));
        pluginMapper.insert(row);
        log.info("L3 外挂注册: {}", name);
        return Map.of("name", name, "level", "L3", "enabled", 0);
    }

    private static String serializeConfig(Object config) {
        if (config == null) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("config 序列化失败");
        }
    }

    /** ZIP 安装：白名单校验 + 落盘 + 装载 + upsert 行（未启用状态）。 */
    public synchronized Map<String, Object> installZip(Path zipPath) throws Exception {
        ApePlugin p = zipLoader.install(zipPath);
        instances.put(p.name(), p);
        upsertRow(p, "L2", Path.of(pluginDir).resolve(p.name()).toString());
        runtimeStates.put(p.name(), "inactive");
        log.info("L2 插件安装: {} v{}", p.name(), p.version());
        return Map.of("name", p.name(), "version", p.version(), "level", "L2",
                "display_name", p.displayName());
    }

    // ---------------------------------------------------------------- 配置

    public Object configOf(String name) {
        String cfg = rowOf(name).getConfig();
        if (cfg == null || cfg.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(cfg, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void saveConfig(String name, Object config) {
        PluginDO row = rowOf(name);
        try {
            row.setConfig(JSON.writeValueAsString(config));
        } catch (Exception e) {
            throw new IllegalArgumentException("config 序列化失败");
        }
        pluginMapper.updateById(row);
    }

    // ---------------------------------------------------------------- 删除

    /** 删除插件（enabled 时先停用；L2 同时删目录）。 */
    public synchronized void delete(String name) throws Exception {
        PluginDO row = rowOf(name);
        if (row.getEnabled() != null && row.getEnabled() == 1) {
            disable(name);
        }
        instances.remove(name);
        ownedTools.remove(name);
        runtimeStates.remove(name);
        lastErrors.remove(name);
        if ("L2".equals(row.getLevel())) {
            deleteRecursively(Path.of(pluginDir).resolve(name));
        }
        pluginMapper.deleteById(row.getId());
        log.info("插件删除: {}", name);
    }

    // ---------------------------------------------------------------- 查询

    /** 列表（对齐 Python _plugin_to_dict 契约）。 */
    public Map<String, Object> page(long page, long pageSize) {
        List<PluginDO> all = pluginMapper.selectList(Wrappers.<PluginDO>lambdaQuery()
                .orderByAsc(PluginDO::getId));
        int from = (int) Math.min((page - 1) * pageSize, all.size());
        int to = (int) Math.min(from + pageSize, all.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PluginDO p : all.subList(from, to)) {
            items.add(toOut(p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("items", items);
        return out;
    }

    /** 行 id → 插件名。 */
    public String nameOf(Long id) {
        PluginDO row = id == null ? null : pluginMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("插件不存在: id=" + id);
        }
        return row.getName();
    }

    /** 行 id → enabled。 */
    public boolean isEnabled(Long id) {
        PluginDO row = id == null ? null : pluginMapper.selectById(id);
        return row != null && row.getEnabled() != null && row.getEnabled() == 1;
    }

    // ---------------------------------------------------------------- 内部

    /** L3 外挂：拉起子进程 → tools/list → 代理工具注册（"plugin:tool" 命名）。 */
    private void startL3(PluginDO row) throws Exception {
        Map<String, Object> cfg;
        try {
            cfg = row.getConfig() == null || row.getConfig().isBlank()
                    ? Map.of() : JSON.readValue(row.getConfig(), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("L3 config 非法: " + row.getName());
        }
        String command = String.valueOf(cfg.getOrDefault("command", ""));
        if (command.isBlank()) {
            throw new IllegalArgumentException("L3 插件缺 command 配置: " + row.getName());
        }
        @SuppressWarnings("unchecked")
        List<String> argList = (List<String>) cfg.get("args");
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) cfg.get("env");
        List<Map<String, Object>> tools = l3Client.start(row.getName(), command, argList, env);

        String pluginName = row.getName();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            String toolName = String.valueOf(t.get("name"));
            String exposed = pluginName + ":" + toolName;
            mcp.registerTool(exposed,
                    "[" + row.getTitle() + "] " + t.get("description"), (String) t.get("description"),
                    (argsJson, userId) -> {
                        try {
                            return l3Client.callTool(pluginName, toolName, argsJson);
                        } catch (Exception e) {
                            throw new RuntimeException(e.getMessage(), e);
                        }
                    },
                    (Map<String, Object>) t.get("input_schema"),
                    List.of(), pluginName, pluginName);
            names.add(exposed);
        }
        ownedTools.put(pluginName, names);
    }

    private ApePlugin.McpBinder mcpBinder(String pluginName) {
        return new ApePlugin.McpBinder() {
            @Override
            public void registerTool(String toolName, String description, String title,
                                     ApePlugin.ToolHandler handler, Map<String, Object> inputSchema,
                                     List<String> requiredPermissions, String category) {
                mcp.registerTool(toolName, description, title,
                        (argsJson, userId) -> {
                            try {
                                return handler.apply(argsJson, userId);
                            } catch (Exception e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        },
                        inputSchema, requiredPermissions, pluginName, category);
            }
        };
    }

    private void upsertRow(ApePlugin p, String level, String modulePath) {
        List<PluginDO> existing = pluginMapper.selectList(Wrappers.<PluginDO>lambdaQuery()
                .eq(PluginDO::getName, p.name()));
        PluginDO row = existing.isEmpty() ? new PluginDO() : existing.get(0);
        row.setName(p.name());
        row.setTitle(p.displayName());
        row.setVersion(p.version());
        row.setLevel(level);
        row.setModulePath(modulePath);
        row.setDescription(p.description());
        row.setAuthor(p.author());
        if (row.getEnabled() == null) {
            row.setEnabled(0);
        }
        if (row.getConfig() == null) {
            row.setConfig("{}");
        }
        if (existing.isEmpty()) {
            pluginMapper.insert(row);
        } else {
            pluginMapper.updateById(row);
        }
    }

    private PluginDO rowOf(String name) {
        List<PluginDO> rows = pluginMapper.selectList(Wrappers.<PluginDO>lambdaQuery()
                .eq(PluginDO::getName, name));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("插件不存在: " + name);
        }
        return rows.get(0);
    }

    private Map<String, Object> toOut(PluginDO p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("display_name", p.getTitle());
        m.put("description", p.getDescription());
        m.put("version", p.getVersion());
        m.put("author", p.getAuthor());
        m.put("module_path", p.getModulePath());
        m.put("level", p.getLevel());
        m.put("enabled", p.getEnabled());
        m.put("config", configOf(p.getName()));
        m.put("runtime_state", runtimeStates.getOrDefault(p.getName(),
                p.getEnabled() != null && p.getEnabled() == 1 ? "active" : "inactive"));
        m.put("last_error", lastErrors.get(p.getName()));
        return m;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
        }
    }
}
