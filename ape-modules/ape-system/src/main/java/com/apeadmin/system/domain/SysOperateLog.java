package com.apeadmin.system.domain;

import com.apeadmin.framework.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_operate_log")
public class SysOperateLog extends BaseDO {

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
    private String ipRegion;
    private String userAgent;
    private Integer success;
    private String failReason;
}
