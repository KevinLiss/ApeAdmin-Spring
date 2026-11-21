package com.apeadmin.system.service;

import com.apeadmin.framework.log.OperateLogEvent;
import com.apeadmin.system.domain.SysOperateLog;
import com.apeadmin.system.mapper.SysOperateLogMapper;
import com.apeadmin.system.security.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 操作日志事件消费方（framework-log 切面发布 OperateLogEvent，这里落库 sys_operate_log）。
 *
 * <p>同步消费（不加 @Async）：切面在请求线程 finally 中发布事件，userId/username
 * 需要借同线程的 UserContextHolder 采样；SQLite WAL 插入毫秒级，无需异步。
 */
@Slf4j
@Component
public class OperateLogListener {

    private final SysOperateLogMapper logMapper;

    public OperateLogListener(SysOperateLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @EventListener
    public void onEvent(OperateLogEvent event) {
        try {
            Long userId = event.getUserId();
            String username = event.getUsername();
            if (username == null || userId == null) {
                var user = UserContextHolder.get();
                if (user != null) {
                    if (userId == null) {
                        userId = user.id();
                    }
                    if (username == null) {
                        username = user.username();
                    }
                }
            }
            SysOperateLog row = new SysOperateLog();
            row.setUserId(userId);
            row.setUsername(username);
            row.setModule(event.getModule());
            row.setAction(event.getAction());
            row.setMethod(event.getMethod());
            row.setPath(event.getPath());
            row.setParams(event.getParams());
            row.setStatusCode(event.getStatusCode());
            row.setDurationMs(event.getDurationMs());
            row.setIp(event.getIp());
            row.setUserAgent(event.getUserAgent());
            row.setSuccess(event.isSuccess() ? 1 : 0);
            row.setFailReason(event.getFailReason());
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("操作日志落库失败（不影响业务）：{}", e.getMessage());
        }
    }
}
