package com.apeadmin.framework.log;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 操作日志事件（切面发布 → 消费方 @Async @EventListener 落库）。
 */
@Data
@Builder
public class OperateLogEvent implements Serializable {

    private Long userId;
    private String username;
    private String module;
    private String action;
    private String method;
    private String path;
    private String params;
    private Integer statusCode;
    private Long durationMs;
    private String ip;
    private String userAgent;
    private boolean success;
    private String failReason;
}
