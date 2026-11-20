package com.apeadmin.system.domain;

import com.apeadmin.framework.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseDO {

    private String username;
    private String password;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Long deptId;
    private Integer isSuperAdmin;
    private Integer status;
    private String lastLoginAt;
    private String lastLoginIp;
}
