package com.apeadmin.framework.mybatis;

/**
 * 数据权限范围（对齐 sys_role.data_scope）。
 */
public enum DataScope {
    /** 仅本人 */
    SELF(1),
    /** 本部门及以下 */
    DEPT_AND_CHILD(2),
    /** 本部门 */
    DEPT(3),
    /** 全部（不做过滤） */
    ALL(4);

    private final int code;

    DataScope(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DataScope ofCode(Integer code) {
        if (code == null) {
            return SELF;
        }
        for (DataScope s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return SELF;
    }
}
