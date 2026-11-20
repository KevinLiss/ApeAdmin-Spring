package com.apeadmin.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 进程内事件总线（对齐 Python 版 EventBus）：插件订阅应用事件。
 *
 * <p>事件：app_startup / app_shutdown / user_login / user_logout。
 * handler 异常被吞（不影响其他订阅者与主流程）。
 */
@Slf4j
@Component
public class EventBus {

    public static final String APP_STARTUP = "app_startup";
    public static final String APP_SHUTDOWN = "app_shutdown";
    public static final String USER_LOGIN = "user_login";
    public static final String USER_LOGOUT = "user_logout";

    private record Sub(String event, Consumer<Map<String, Object>> handler,
                      int priority, String pluginName) {
    }

    private final Map<String, List<Sub>> subs = new ConcurrentHashMap<>();

    /** 订阅（priority 小者先执行）。 */
    public void on(String event, Consumer<Map<String, Object>> handler,
                   int priority, String pluginName) {
        subs.compute(event, (k, list) -> {
            List<Sub> l = list == null ? new ArrayList<>() : new ArrayList<>(list);
            l.add(new Sub(event, handler, priority, pluginName));
            l.sort(Comparator.comparingInt(Sub::priority));
            return l;
        });
    }

    public void on(String event, Consumer<Map<String, Object>> handler, String pluginName) {
        on(event, handler, 0, pluginName);
    }

    /** 发布事件（同步顺序执行；异常吞掉）。 */
    public void emit(String event, Map<String, Object> payload) {
        List<Sub> list = subs.get(event);
        if (list == null) {
            return;
        }
        for (Sub s : list) {
            try {
                s.handler().accept(payload == null ? Map.of() : payload);
            } catch (Exception e) {
                log.error("事件处理器异常 [{}] plugin={}: {}", event, s.pluginName(), e.getMessage());
            }
        }
    }

    /** 移除某插件全部订阅，返回移除数（插件停用时调用）。 */
    public int offPlugin(String pluginName) {
        int removed = 0;
        for (Map.Entry<String, List<Sub>> e : subs.entrySet()) {
            List<Sub> kept = e.getValue().stream()
                    .filter(s -> !s.pluginName().equals(pluginName)).toList();
            removed += e.getValue().size() - kept.size();
            if (kept.isEmpty()) {
                subs.remove(e.getKey());
            } else {
                subs.put(e.getKey(), new ArrayList<>(kept));
            }
        }
        return removed;
    }

    /** 调试用：订阅计数。 */
    public Map<String, Integer> stats() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        subs.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }
}
