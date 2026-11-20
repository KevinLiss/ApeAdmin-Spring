package com.apeadmin.system.security;

import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户 30s TTL 缓存（数据权限/上下文高频查询）。
 */
@Component
public class UserCacheService {

    private record Entry(SysUser user, long expireAt) {
    }

    private static final long TTL_MS = 30_000;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final SysUserMapper userMapper;

    public UserCacheService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public static LoginUser toLoginUser(SysUser user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getNickname(),
                user.getIsSuperAdmin() != null && user.getIsSuperAdmin() == 1);
    }

    public static LoginUser get(Long userId) {
        return SpringHolder.get().cachedLoginUser(userId);
    }

    /** 通过静态 holder 解 Bean，避免 UserContextHolder 静态调用注入链过长。 */
    public LoginUser cachedLoginUser(Long userId) {
        Entry e = cache.get(userId);
        long now = System.currentTimeMillis();
        if (e != null && e.expireAt() > now) {
            return toLoginUser(e.user());
        }
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, 1)
                .last("LIMIT 1"));
        SysUser user = users.isEmpty() ? null : users.get(0);
        if (user == null) {
            cache.remove(userId);
            return null;
        }
        cache.put(userId, new Entry(user, now + TTL_MS));
        return toLoginUser(user);
    }

    public SysUser cachedRaw(Long userId) {
        Entry e = cache.get(userId);
        if (e != null && e.expireAt() > System.currentTimeMillis()) {
            return e.user();
        }
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            cache.put(userId, new Entry(user, System.currentTimeMillis() + TTL_MS));
        }
        return user;
    }

    public void evict(Long userId) {
        cache.remove(userId);
    }

    /** 延迟取 Bean 的静态桥（Spring 上下文就绪后有效）。 */
    static class SpringHolder {
        private static UserCacheService instance;

        static UserCacheService get() {
            return instance;
        }

        static void register(UserCacheService service) {
            instance = service;
        }
    }

    public UserCacheService self() {
        SpringHolder.register(this);
        return this;
    }
}
