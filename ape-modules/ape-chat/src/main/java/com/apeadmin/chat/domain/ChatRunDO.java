package com.apeadmin.chat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_chat_run：一次生成任务（Run 架构，与 HTTP 生命周期解耦）。 */
@Data
@TableName("sys_chat_run")
public class ChatRunDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private String status;         // pending/running/done/failed/stopped
    private Long providerId;
    private String model;
    private Integer enableTools;
    private String question;
    private String answer;         // 增量节流落库（500ms）
    private String toolEvents;     // JSON
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer durationMs;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
