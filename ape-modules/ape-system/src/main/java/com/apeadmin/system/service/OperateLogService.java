package com.apeadmin.system.service;

import com.apeadmin.framework.log.OperateLogEvent;
import com.apeadmin.system.domain.SysOperateLog;
import com.apeadmin.system.mapper.SysOperateLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志异步消费（@Async @EventListener，RVP 事件化模式）。
 */
@Slf4j
@Service
public class OperateLogService {

    private final SysOperateLogMapper operateLogMapper;

    public OperateLogService(SysOperateLogMapper operateLogMapper) {
        this.operateLogMapper = operateLogMapper;
    }

    @Async
    @EventListener
    public void onOperateLog(OperateLogEvent event) {
        try {
            SysOperateLog entry = new SysOperateLog();
            entry.setUserId(event.getUserId());
            entry.setUsername(event.getUsername());
            entry.setModule(event.getModule());
            entry.setAction(event.getAction());
            entry.setMethod(event.getMethod());
            entry.setPath(event.getPath());
            entry.setParams(event.getParams());
            entry.setStatusCode(event.getStatusCode());
            entry.setDurationMs(event.getDurationMs());
            entry.setIp(event.getIp());
            entry.setUserAgent(event.getUserAgent());
            entry.setSuccess(event.isSuccess() ? 1 : 0);
            entry.setFailReason(event.getFailReason());
            entry.setCreatedAt(LocalDateTime.now());
            operateLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("操作日志落库失败（不影响业务）", e);
        }
    }
}
