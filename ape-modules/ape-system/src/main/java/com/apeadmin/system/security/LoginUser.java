package com.apeadmin.system.security;

/**
 * 登录用户上下文（JWT extra 携带高频字段，RVP LoginHelper 模式）。
 */
public record LoginUser(Long id, String username, String nickname, boolean superAdmin) {
}
