package com.apeadmin.plugin.builtin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** dev_example_note：插件示例表（V1.4 建表；表名以插件名前缀）。 */
@Data
@TableName("dev_example_note")
public class DevExampleNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
