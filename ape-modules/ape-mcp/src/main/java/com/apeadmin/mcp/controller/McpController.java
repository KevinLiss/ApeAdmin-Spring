package com.apeadmin.mcp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.mcp.McpManager;
import com.apeadmin.mcp.McpService;
import com.apeadmin.system.security.PermissionService;
import com.apeadmin.system.security.UserContextHolder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP HTTP 端点（对齐前端 /mcp 契约）：
 *
 * <pre>
 * GET  /mcp/tools              — 工具列表（按用户权限过滤）
 * GET  /mcp/tools/categories   — 分类统计
 * POST /mcp/tools/call         — 调用工具（需 mcp:tools:call）
 * GET  /mcp/resources         — 资源列表
 * GET  /mcp/resources/read     — 读资源
 * GET  /mcp/prompts           — 提示词列表
 * POST /mcp/prompts/render    — 渲染提示词
 * GET  /mcp/audit-logs        — 审计日志（分页 + 过滤）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpManager manager;
    private final McpService service;
    private final PermissionService permissionService;

    @Data
    public static class CallToolReq {
        private String name;
        private Map<String, Object> arguments;
    }

    @Data
    public static class RenderPromptReq {
        private String name;
        private Map<String, Object> arguments;
    }

    // ---------------------------------------------------------------- Tools

    @SaCheckLogin
    @GetMapping("/tools")
    public R<List<Map<String, Object>>> listTools() {
        var ctx = UserContextHolder.require();
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpManager.Tool t : manager.listTools(permissionService.permissionsOf(ctx.id()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("input_schema", t.getInputSchema());
            m.put("category", t.getCategory());
            m.put("plugin_name", t.getPluginName());
            m.put("required_permissions", t.getRequiredPermissions());
            out.add(m);
        }
        return R.ok(out);
    }

    @SaCheckLogin
    @GetMapping("/tools/categories")
    public R<List<Map<String, Object>>> listCategories() {
        return R.ok(manager.listCategories());
    }

    @SaCheckPermission(PermConst.MCP_TOOLS_CALL)
    @PostMapping("/tools/call")
    public R<Map<String, Object>> callTool(@RequestBody CallToolReq body) {
        var ctx = UserContextHolder.require();
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工具名 name 不能为空");
        }
        var permissions = permissionService.permissionsOf(ctx.id());

        // 工具存在性 / 可见性（区分不存在与无权限）
        var available = manager.listTools(permissions);
        if (available.stream().noneMatch(t -> t.getName().equals(body.getName()))) {
            if (manager.getTool(body.getName()) == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "工具不存在: " + body.getName());
            }
            throw new BizException(ErrorCode.FORBIDDEN, "当前用户无权调用工具: " + body.getName());
        }

        long startedAt = System.currentTimeMillis();
        try {
            String result = manager.callTool(body.getName(),
                    serialize(body.getArguments()), ctx.id());
            service.writeAudit("tool", body.getName(), ctx.id(), ctx.username(),
                    body.getArguments(), result, "success");
            return R.ok(Map.of("result", result));
        } catch (java.util.concurrent.TimeoutException e) {
            service.writeAudit("tool", body.getName(), ctx.id(), ctx.username(),
                    body.getArguments(), null, "failed");
            throw new BizException(ErrorCode.INTERNAL_ERROR, "工具执行超时: " + body.getName());
        } catch (Exception e) {
            service.writeAudit("tool", body.getName(), ctx.id(), ctx.username(),
                    body.getArguments(), null, "failed");
            throw new BizException(ErrorCode.INTERNAL_ERROR, "工具执行失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Resources

    @SaCheckLogin
    @GetMapping("/resources")
    public R<List<Map<String, Object>>> listResources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpManager.Resource r : manager.listResources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", r.getUri());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("mime_type", r.getMimeType());
            out.add(m);
        }
        return R.ok(out);
    }

    @SaCheckLogin
    @GetMapping("/resources/read")
    public R<Map<String, Object>> readResource(@RequestParam String uri) {
        var ctx = UserContextHolder.require();
        try {
            String content = manager.readResource(uri);
            service.writeAudit("resource", uri, ctx.id(), ctx.username(), null,
                    content, "success");
            return R.ok(Map.of("uri", uri, "content", content));
        } catch (IllegalArgumentException e) {
            service.writeAudit("resource", uri, ctx.id(), ctx.username(), null, null, "failed");
            throw new BizException(ErrorCode.NOT_FOUND, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Prompts

    @SaCheckLogin
    @GetMapping("/prompts")
    public R<List<Map<String, Object>>> listPrompts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpManager.Prompt p : manager.listPrompts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("description", p.getDescription());
            m.put("arguments", p.getArguments());
            out.add(m);
        }
        return R.ok(out);
    }

    @SaCheckLogin
    @PostMapping("/prompts/render")
    public R<Map<String, Object>> renderPrompt(@RequestBody RenderPromptReq body) {
        var ctx = UserContextHolder.require();
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词名 name 不能为空");
        }
        try {
            String rendered = manager.renderPrompt(body.getName(), body.getArguments());
            service.writeAudit("prompt", body.getName(), ctx.id(), ctx.username(),
                    body.getArguments(), rendered, "success");
            return R.ok(Map.of("rendered", rendered));
        } catch (IllegalArgumentException e) {
            service.writeAudit("prompt", body.getName(), ctx.id(), ctx.username(),
                    body.getArguments(), null, "failed");
            throw new BizException(ErrorCode.NOT_FOUND, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Audit

    @SaCheckPermission(PermConst.MCP_AUDIT_LIST)
    @GetMapping("/audit-logs")
    public R<Map<String, Object>> auditLogs(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(name = "page_size", defaultValue = "20") long pageSize,
                                           @RequestParam(name = "action_type", required = false) String actionType,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(service.auditPage(page, pageSize, actionType, keyword));
    }

    private static String serialize(Object o) {
        if (o == null) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
