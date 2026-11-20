package com.apeadmin.chat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_chat_message：会话消息（user/assistant/system/tool）。 */
@Data
@TableName("sys_chat_message")
public class ChatMessageDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;            // 消息↔运行关联（修 Go 版孤儿缺口）
    private String role;
    private String content;
    private String toolEvents;     // JSON：[{type:tool_call|tool_result,...}]
    private Integer roundNumber;
    private Integer promptTokens;
    private Integer completionTokens;
    private String status;         // generating/done/failed/stopped
    private Integer version;       // 乐观锁（整轮幂等落库）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
