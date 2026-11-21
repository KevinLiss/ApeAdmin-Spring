package com.apeadmin.chat.service;

import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每用户每日 AI 调用配额（内存计数；0=不限制），对齐 Python 版
 * chat.py 的 _check_chat_quota 语义。重启清零是两端一致的权衡。
 *
 * <p>Python 版在 /ai/chat 与 /ai/chat/stream 均检查；Java 版同样覆盖
 * runs 架构与直连两个路径。
 */
@Component
public class ChatQuotaService {

    @Value("${ape.chat.daily-limit:100}")
    private long dailyLimit;

    private final Map<Long, Map<String, Long>> dailyUsage = new ConcurrentHashMap<>();

    /** 超限抛 429；计数在通过检查后 +1（与 Python 版一致）。 */
    public void check(Long userId) {
        if (dailyLimit <= 0) {
            return;
        }
        long today = Long.parseLong(LocalDate.now().toString().replace("-", ""));
        Map<String, Long> entry = dailyUsage.computeIfAbsent(userId, k -> {
            Map<String, Long> m = new ConcurrentHashMap<>();
            m.put("date", 0L);
            m.put("count", 0L);
            return m;
        });
        if (entry.get("date") != today) {
            entry.put("date", today);
            entry.put("count", 0L);
        }
        if (entry.get("count") >= dailyLimit) {
            throw new BizException(ErrorCode.TOO_MANY_ATTEMPTS,
                    "今日 AI 调用次数已达上限（" + dailyLimit + " 次/天），请明日再试或联系管理员调整配额");
        }
        entry.merge("count", 1L, Long::sum);
    }
}
