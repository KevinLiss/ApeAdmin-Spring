package com.apeadmin.plugin.builtin;

import com.apeadmin.plugin.ApePlugin;
import com.apeadmin.plugin.mapper.DevExampleNoteMapper;
import com.apeadmin.plugin.EventBus;
import com.apeadmin.system.domain.SysMenu;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 插件开发示例（Java 版备忘录，对齐 Python dev_example）。
 *
 * <p>演示完整插件要素：表（dev_example_note，V1.4 建表）、REST CRUD
 * （DevExampleController，权限 dev_example:*）、MCP 工具（note_list/note_create/
 * note_stats）、菜单 seed（install 时注册插件管理子菜单）、事件订阅（USER_LOGIN 计数）。
 *
 * <p>复制本类 + 改名即可作为新 L1 插件模板（表/权限/菜单以插件名前缀）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevExamplePlugin implements ApePlugin {

    static final ObjectMapper JSON = new ObjectMapper();

    private final DevExampleNoteMapper noteMapper;
    private final SysMenuMapper menuMapper;

    /** USER_LOGIN 计数（事件订阅演示）。 */
    private final AtomicLong loginCount = new AtomicLong();

    @Override
    public String name() {
        return "dev_example";
    }

    @Override
    public String displayName() {
        return "插件开发示例";
    }

    @Override
    public String description() {
        return "完整的插件开发示例——包含数据库模型、API 路由、菜单注册、事件订阅、MCP 工具";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String author() {
        return "ApeAdmin";
    }

    /** 安装：seed 菜单（幂等）+ 示例备忘。 */
    @Override
    public void install() {
        seedMenus();
        if (noteMapper.selectCount(null) == 0) {
            DevExampleNote note = new DevExampleNote();
            note.setTitle("欢迎使用插件体系");
            note.setContent("这是 dev_example 插件创建的第一条备忘录。");
            noteMapper.insert(note);
        }
        log.info("[DevExample] install 完成（菜单 + 示例数据）");
    }

    @Override
    public List<String> registerRuntime(McpBinder mcp, EventBus bus) {
        // MCP 工具：note_list / note_create / note_stats
        mcp.registerTool("note_list", "获取备忘录列表（标题 + 内容摘要）。", "备忘录列表",
                this::toolList, schema(
                        prop("keyword", "string", "标题关键词过滤")),
                List.of(), name());
        mcp.registerTool("note_create", "创建一条备忘录。需要标题与内容。", "创建备忘录",
                this::toolCreate, schema(
                        prop("title", "string", "标题"), prop("content", "string", "内容")),
                List.of(), name());
        mcp.registerTool("note_stats", "备忘录统计：总数与登录事件计数。", "备忘录统计",
                this::toolStats, schema(),
                List.of(), name());

        // 事件订阅：USER_LOGIN 计数（演示 EventBus）
        bus.on(EventBus.USER_LOGIN, payload -> {
            loginCount.incrementAndGet();
            log.debug("[DevExample] user_login 事件 #{}", loginCount.get());
        }, name());

        return List.of("note_list", "note_create", "note_stats");
    }

    @Override
    public void uninstall() {
        // 停用保留数据（uninstall 语义：清理菜单；表与数据保留给重新启用）
        List<SysMenu> own = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .likeRight(SysMenu::getPermission, "dev_example:"));
        for (SysMenu m : own) {
            menuMapper.deleteById(m.getId());
        }
        log.info("[DevExample] uninstall 完成（菜单已清理，数据保留）");
    }

    // ---------------------------------------------------------------- MCP 工具实现

    private String toolList(String argsJson, Long userId) throws Exception {
        Map<String, Object> a = parse(argsJson);
        String keyword = a.get("keyword") == null ? null : String.valueOf(a.get("keyword"));
        List<DevExampleNote> notes = noteMapper.selectList(Wrappers.<DevExampleNote>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), DevExampleNote::getTitle, keyword)
                .orderByDesc(DevExampleNote::getId));
        return JSON.writeValueAsString(Map.of("total", notes.size(), "items",
                notes.stream().map(n -> Map.of(
                        "id", n.getId(), "title", n.getTitle(),
                        "content", n.getContent() == null ? "" : n.getContent())).toList()));
    }

    private String toolCreate(String argsJson, Long userId) throws Exception {
        Map<String, Object> a = parse(argsJson);
        String title = a.get("title") == null ? "" : String.valueOf(a.get("title"));
        String content = a.get("content") == null ? "" : String.valueOf(a.get("content"));
        if (title.isBlank()) {
            return "{\"error\":\"标题必填\"}";
        }
        DevExampleNote n = new DevExampleNote();
        n.setTitle(title);
        n.setContent(content);
        noteMapper.insert(n);
        return JSON.writeValueAsString(Map.of("id", n.getId(), "title", title, "message", "备忘录创建成功"));
    }

    private String toolStats(String argsJson, Long userId) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "total_notes", noteMapper.selectCount(null),
                "login_events", loginCount.get()));
    }

    // ---------------------------------------------------------------- 辅助

    private void seedMenus() {
        if (menuMapper.selectCount(Wrappers.<SysMenu>lambdaQuery()
                .likeRight(SysMenu::getPermission, "dev_example:")) > 0) {
            return;   // 幂等
        }
        // 挂在「插件管理」目录下（menu id=40），组件复用 apehub 示例页结构占位
        SysMenu page = new SysMenu();
        page.setParentId(40L);
        page.setType("C");
        page.setName("备忘录示例");
        page.setPath("dev-example");
        page.setComponent("system/plugin/index");   // 复用插件页占位（无专属前端时可见即算注册成功）
        page.setPermission("dev_example:list");
        page.setIcon("Notebook");
        page.setSort(10);
        page.setVisible(1);
        page.setStatus(1);
        menuMapper.insert(page);

        SysMenu btn = new SysMenu();
        btn.setParentId(page.getId());
        btn.setType("F");
        btn.setName("新增备忘");
        btn.setPermission("dev_example:add");
        btn.setSort(1);
        btn.setVisible(1);
        btn.setStatus(1);
        menuMapper.insert(btn);
        log.info("[DevExample] 菜单 seed 完成（{} / {}）", page.getId(), btn.getId());
    }

    @SuppressWarnings("unchecked")
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

    @SafeVarargs
    private static Map<String, Object> schema(Map.Entry<String, Object>... entries) {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            props.put(e.getKey(), e.getValue());
        }
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", props);
        return p;
    }

    private static Map.Entry<String, Object> prop(String name, String type, String desc) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("type", type);
        p.put("description", desc);
        return Map.entry(name, p);
    }
}
