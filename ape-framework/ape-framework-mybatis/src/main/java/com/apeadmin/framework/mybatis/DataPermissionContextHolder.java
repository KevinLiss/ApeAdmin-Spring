package com.apeadmin.framework.mybatis;

/**
 * 数据权限 ThreadLocal 上下文（切面写入 → 拦截器读取 → finally 清理）。
 */
public final class DataPermissionContextHolder {

    private static final ThreadLocal<DataPermission> CTX = new ThreadLocal<>();

    private DataPermissionContextHolder() {
    }

    public static void set(DataPermission annotation) {
        CTX.set(annotation);
    }

    public static DataPermission get() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }
}
