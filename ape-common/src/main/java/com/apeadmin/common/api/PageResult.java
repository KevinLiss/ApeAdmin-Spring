package com.apeadmin.common.api;

import java.util.List;

/**
 * 分页响应（对齐 Python 版契约：{total, items}）。
 */
public record PageResult<T>(long total, List<T> items) {

    public static <T> PageResult<T> of(long total, List<T> items) {
        return new PageResult<>(total, items == null ? List.of() : items);
    }
}
