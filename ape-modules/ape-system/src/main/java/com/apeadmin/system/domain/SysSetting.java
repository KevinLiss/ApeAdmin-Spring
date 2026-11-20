package com.apeadmin.system.domain;

import com.apeadmin.framework.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_setting")
public class SysSetting extends BaseDO {

    private String key;
    private String value;
    private Integer isPublic;
    private String remark;
}
