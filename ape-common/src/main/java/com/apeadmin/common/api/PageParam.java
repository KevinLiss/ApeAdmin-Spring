package com.apeadmin.common.api;

import java.util.List;

/**
 * 分页请求参数（对齐 Python 版 page/page_size 语义）。
 */
public record PageParam(Integer page, Integer pageSize) {

    public PageParam {
        if (page == null || page < 1) {
            page = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
