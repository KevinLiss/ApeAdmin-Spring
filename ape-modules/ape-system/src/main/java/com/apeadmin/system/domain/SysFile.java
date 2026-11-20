package com.apeadmin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_file：上传文件（磁盘存储于 ./uploads/{stored_name}）。 */
@Data
@TableName("sys_file")
public class SysFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long folderId;
    private String originName;
    private String storedName;
    private String mime;
    private Long size;
    private String md5;
    private Long uploaderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
