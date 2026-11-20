package com.apeadmin.chat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_chat_session：对话会话。 */
@Data
@TableName("sys_chat_session")
public class ChatSessionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private Long providerId;
    private String model;
    private String systemPrompt;   // 会话级快照（插件可注入人设）
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
