package com.apeadmin.common.api;

/**
 * 业务错误码：http 为包封响应的 code 值（对齐 Python 版语义），同时兼作 HTTP 状态。
 */
public enum ErrorCode {
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    TOO_MANY_ATTEMPTS(429, "尝试次数过多，请稍后再试"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int http;
    private final String msg;

    ErrorCode(int http, String msg) {
        this.http = http;
        this.msg = msg;
    }

    public int http() {
        return http;
    }

    public String msg() {
        return msg;
    }
}
