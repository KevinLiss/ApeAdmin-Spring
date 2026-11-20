package com.apeadmin.system.service;

import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.system.domain.SysLoginLog;
import com.apeadmin.system.mapper.SysLoginLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破（RVP 计数模式，M1 内存实现；走计数器抽象 M5 可平移 Redis）。
 * 同 (username:ip) 连续失败 maxFail 次锁定 lockMinutes 分钟。
 */
@Service
public class LoginGuardService {

    private record Attempt(int fails, long lockedUntil, long windowStart) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Value("${ape.login-guard.max-fail:5}")
    private int maxFail;

    @Value("${ape.login-guard.lock-minutes:10}")
    private int lockMinutes;

    @Value("${ape.login-guard.window-minutes:15}")
    private int windowMinutes;

    public void checkLock(String username, String ip) {
        Attempt a = attempts.get(key(username, ip));
        if (a != null && a.lockedUntil() > System.currentTimeMillis()) {
            long minutes = Math.max(1, (a.lockedUntil() - System.currentTimeMillis()) / 60000 + 1);
            throw new BizException(ErrorCode.TOO_MANY_ATTEMPTS, "尝试次数过多，请 " + minutes + " 分钟后再试");
        }
    }

    public void recordFailure(String username, String ip) {
        String k = key(username, ip);
        long now = System.currentTimeMillis();
        Attempt old = attempts.get(k);
        int fails = (old == null || now - old.windowStart() > windowMinutes * 60_000L) ? 1 : old.fails() + 1;
        long lockedUntil = fails >= maxFail ? now + lockMinutes * 60_000L : 0;
        attempts.put(k, new Attempt(fails, lockedUntil, old != null ? old.windowStart() : now));
    }

    public void recordSuccess(String username, String ip) {
        attempts.remove(key(username, ip));
    }

    /** 登录日志独立表（等保证据链，不与操作日志混表）。 */
    public void log(SysLoginLogMapper mapper, SysLoginLog entry) {
        mapper.insert(entry);
    }

    private String key(String username, String ip) {
        return username + ":" + ip;
    }
}
