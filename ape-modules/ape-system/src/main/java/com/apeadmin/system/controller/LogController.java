package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.system.domain.SysOperateLog;
import com.apeadmin.system.mapper.SysOperateLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统操作日志（对齐 Python 版 /logs 契约；数据由 OperateLogAspect 注解驱动落库）：
 *
 * <pre>
 * GET    /logs      — 分页列表（method/path/status_code 过滤）
 * GET    /logs/{id} — 详情
 * DELETE /logs/{id} — 单条删除（物理删）
 * DELETE /logs      — 清空
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    private final SysOperateLogMapper logMapper;

    public LogController(SysOperateLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @SaCheckPermission(PermConst.LOG_LIST)
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String method,
                                       @RequestParam(required = false) String path,
                                       @RequestParam(name = "status_code", required = false) Integer statusCode) {
        var wrapper = Wrappers.<SysOperateLog>query();
        if (method != null && !method.isBlank()) {
            wrapper.eq("method", method);
        }
        if (path != null && !path.isBlank()) {
            wrapper.like("path", path);
        }
        if (statusCode != null) {
            wrapper.eq("status_code", statusCode);
        }
        long total = logMapper.selectCount(wrapper);
        List<Map<String, Object>> items = logMapper.selectList(wrapper
                        .orderByDesc("id")
                        .last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize)).stream()
                .map(LogController::toDict).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        return R.ok(data);
    }

    @SaCheckPermission(PermConst.LOG_LIST)
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        SysOperateLog log = logMapper.selectById(id);
        if (log == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "日志不存在");
        }
        return R.ok(toDict(log));
    }

    @OperateLog(action = "删除日志", module = "系统日志")
    @SaCheckPermission(PermConst.LOG_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (logMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "日志不存在");
        }
        logMapper.deleteById(id);
        return R.ok(null, "删除成功");
    }

    @OperateLog(action = "清空日志", module = "系统日志")
    @SaCheckPermission(PermConst.LOG_DELETE)
    @DeleteMapping
    public R<Void> clear() {
        logMapper.delete(null);
        return R.ok(null, "日志已清空");
    }

    private static Map<String, Object> toDict(SysOperateLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("user_id", log.getUserId());
        m.put("username", log.getUsername());
        m.put("module", log.getModule());
        m.put("action", log.getAction());
        m.put("method", log.getMethod());
        m.put("path", log.getPath());
        m.put("params", log.getParams());
        m.put("status_code", log.getStatusCode());
        m.put("duration_ms", log.getDurationMs());
        m.put("ip", log.getIp());
        m.put("user_agent", log.getUserAgent());
        m.put("error", log.getFailReason());
        m.put("created_at", log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        return m;
    }
}
