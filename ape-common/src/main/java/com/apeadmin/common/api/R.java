package com.apeadmin.common.api;

/**
 * 统一响应包封（对齐 Python 版契约：code=200 表示成功，前端以此为判据）。
 */
public record R<T>(int code, String msg, T data) {

    public static <T> R<T> ok() {
        return new R<>(200, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(200, "ok", data);
    }

    public static <T> R<T> ok(T data, String msg) {
        return new R<>(200, msg, data);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return new R<>(ec.http(), ec.msg(), null);
    }

    public static <T> R<T> fail(ErrorCode ec, String msg) {
        return new R<>(ec.http(), msg, null);
    }
}
