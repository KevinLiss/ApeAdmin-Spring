package com.apeadmin.system.security;

import cn.dev33.satoken.stp.StpUtil;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;

/**
 * 当前用户上下文：身份只信 token 注入，绝不信入参（防越权纪律）。
 * 高频字段（username/nickname/superAdmin/deptId/dataScope）存 JWT extra，免查库。
 */
public final class UserContextHolder {

    private UserContextHolder() {
    }

    /** 获取当前登录用户；未登录抛 401（受保护端点专用）。 */
    public static LoginUser require() {
        LoginUser u = get();
        if (u == null) {
            throw BizException.unauthorized("登录已过期，请重新登录");
        }
        return u;
    }

    /** 获取当前登录用户；未登录返回 null（公共/内部调用）。 */
    public static LoginUser get() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        return new LoginUser(
                Long.valueOf(loginId.toString()),
                (String) StpUtil.getExtra("username"),
                (String) StpUtil.getExtra("nickname"),
                Boolean.TRUE.equals(StpUtil.getExtra("superAdmin")));
    }

    /** 数据权限辅助：当前用户最宽数据范围（登录时写入 extra）。 */
    public static Integer currentDataScope() {
        Object v = StpUtil.getExtra("dataScope");
        return v == null ? null : Integer.valueOf(v.toString());
    }

    public static Long currentDeptId() {
        Object v = StpUtil.getExtra("deptId");
        return v == null ? null : Long.valueOf(v.toString());
    }
}
