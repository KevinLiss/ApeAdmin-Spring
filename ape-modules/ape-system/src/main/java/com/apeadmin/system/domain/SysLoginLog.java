package com.apeadmin.system.domain;

import com.apeadmin.framework.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends BaseDO {

    private Long userId;
    private String username;
    private String ip;
    private String userAgent;
    private String result;
    private String failReason;
}
