package com.apeadmin.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_mcp_audit_log：MCP 调用审计。 */
@Data
@TableName("sys_mcp_audit_log")
public class McpAuditDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String actionType;     // tool/resource/prompt
    private String targetName;
    private String arguments;
    private String resultPreview;
    private String status;        // ok / failed
    private Integer durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
