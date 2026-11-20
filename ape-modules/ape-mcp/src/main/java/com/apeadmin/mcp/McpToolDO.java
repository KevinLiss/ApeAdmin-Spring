package com.apeadmin.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_mcp_tool：工具注册持久化（重启恢复引用）。 */
@Data
@TableName("sys_mcp_tool")
public class McpToolDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String title;
    private String description;
    private String inputSchema;
    private String category;
    private String pluginName;
    private String requiredPermissions;
    private String handlerRef;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
