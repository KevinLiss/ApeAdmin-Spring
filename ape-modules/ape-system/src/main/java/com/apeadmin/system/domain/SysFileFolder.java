package com.apeadmin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_file_folder：文件管理夹（树形，parent_id=0 根）。 */
@Data
@TableName("sys_file_folder")
public class SysFileFolder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String name;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
