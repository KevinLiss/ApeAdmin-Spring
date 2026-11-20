package com.apeadmin.system.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户响应（对齐 Python 版列表字段；密码不外露）。
 */
public record UserResp(Long id, String username, String nickname, String email, String phone,
                       String avatar, Long deptId, Integer isSuperAdmin, Integer status,
                       String lastLoginAt, LocalDateTime createdAt, List<Long> roleIds) {
}
