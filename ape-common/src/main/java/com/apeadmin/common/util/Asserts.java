package com.apeadmin.common.util;

import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;

/**
 * 断言工具：条件不满足即抛 BizException。
 */
public final class Asserts {

    private Asserts() {
    }

    public static void that(boolean condition, ErrorCode error, String message) {
        if (!condition) {
            throw new BizException(error, message);
        }
    }

    public static void notBlank(String value, String message) {
        that(value != null && !value.isBlank(), ErrorCode.BAD_REQUEST, message);
    }

    public static void notNull(Object value, String message) {
        that(value != null, ErrorCode.BAD_REQUEST, message);
    }
}
