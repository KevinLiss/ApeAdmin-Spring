package com.apeadmin.common.api;

/**
 * 业务异常：message 为对外提示语，error 携带错误码；由全局异常处理器转为 R 包封。
 */
public class BizException extends RuntimeException {

    private final ErrorCode error;

    public BizException(ErrorCode error) {
        super(error.msg());
        this.error = error;
    }

    public BizException(ErrorCode error, String message) {
        super(message);
        this.error = error;
    }

    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }

    public ErrorCode error() {
        return error;
    }
}
