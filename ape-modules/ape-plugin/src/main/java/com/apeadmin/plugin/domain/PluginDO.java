package com.apeadmin.plugin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_plugin：插件注册持久化。 */
@Data
@TableName("sys_plugin")
public class PluginDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;           // 唯一
    private String title;          // display_name
    private String version;
    private String level;          // L1 / L2 / L3
    private String modulePath;
    private Integer enabled;
    private String config;         // JSON
    private String description;
    private String author;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
