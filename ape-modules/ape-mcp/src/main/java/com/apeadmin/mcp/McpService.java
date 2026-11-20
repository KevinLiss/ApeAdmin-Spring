package com.apeadmin.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 持久化 + 审计（sys_mcp_tool / sys_mcp_audit_log）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpService implements McpManager.McpPersistence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final com.apeadmin.mcp.mapper.McpToolMapper toolMapper;
    private final com.apeadmin.mcp.mapper.McpAuditMapper auditMapper;

    // ---------------------------------------------------------------- persistence

    @Override
    public void upsertTool(McpManager.Tool t) {
        try {
            var existing = toolMapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                    .eq(McpToolDO::getName, t.getName()));
            McpToolDO row = existing.isEmpty() ? new McpToolDO() : existing.get(0);
            row.setName(t.getName());
            row.setTitle(t.getTitle());
            row.setDescription(t.getDescription());
            row.setInputSchema(serialize(t.getInputSchema()));
            row.setCategory(t.getCategory());
            row.setPluginName(t.getPluginName());
            row.setRequiredPermissions(serialize(t.getRequiredPermissions()));
            row.setHandlerRef("builtin:" + t.getName());
            row.setEnabled(1);
            if (existing.isEmpty()) {
                toolMapper.insert(row);
            } else {
                toolMapper.updateById(row);
            }
        } catch (Exception e) {
            log.debug("MCP persist skipped for '{}': {}", t.getName(), e.getMessage());
        }
    }

    @Override
    public void markToolDisabled(String name) {
        try {
            var existing = toolMapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                    .eq(McpToolDO::getName, name));
            if (!existing.isEmpty()) {
                McpToolDO row = existing.get(0);
                row.setEnabled(0);
                toolMapper.updateById(row);
            }
        } catch (Exception e) {
            log.debug("MCP disable persist skipped for '{}': {}", name, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- audit

    /** 审计写日志（失败不阻断主流程）。 */
    public void writeAudit(String actionType, String targetName, Long userId, String username,
                           Object arguments, Object result, String status) {
        try {
            McpAuditDO logRow = new McpAuditDO();
            logRow.setActionType(actionType);
            logRow.setTargetName(targetName);
            logRow.setUserId(userId);
            logRow.setUsername(username);
            logRow.setArguments(truncate(serialize(arguments)));
            logRow.setResultPreview(truncate(serialize(result)));
            logRow.setStatus(status);
            auditMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("MCP 审计写入失败: {}", e.getMessage());
        }
    }

    /** 审计分页（action_type / keyword 过滤）。 */
    public Map<String, Object> auditPage(long page, long pageSize, String actionType, String keyword) {
        var q = Wrappers.<McpAuditDO>lambdaQuery()
                .eq(actionType != null && !actionType.isBlank(), McpAuditDO::getActionType, actionType)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(McpAuditDO::getTargetName, keyword)
                        .or().like(McpAuditDO::getUsername, keyword))
                .orderByDesc(McpAuditDO::getId);
        long total = auditMapper.selectCount(q);
        List<McpAuditDO> rows = auditMapper.selectList(q
                .last("LIMIT " + pageSize + " OFFSET " + ((page - 1) * pageSize)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpAuditDO r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("action_type", r.getActionType());
            m.put("target_name", r.getTargetName());
            m.put("arguments", r.getArguments());
            m.put("result_preview", r.getResultPreview());
            m.put("status", r.getStatus());
            m.put("user_id", r.getUserId());
            m.put("username", r.getUsername());
            m.put("created_at", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("items", items);
        return out;
    }

    private static String serialize(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
