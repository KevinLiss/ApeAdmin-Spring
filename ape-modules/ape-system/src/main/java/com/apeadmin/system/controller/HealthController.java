package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点。
 *
 * <p>注意：前端「重启等待」轮询（utils/restart.ts → pollBackendHealth）依赖
 * 响应信封里的 {@code data.pid} 判断「新进程是否已拉起」——必须返回 R 包封 + pid，
 * 否则更新/重启流程永远判定超时（实证：2026-11-21 版本更新弹窗）。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @Value("${ape.app.version:1.0.0-SNAPSHOT}")
    private String appVersion;

    @RequestMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "up");
        m.put("app", "apeadmin-spring");
        m.put("version", appVersion);
        m.put("pid", ProcessHandle.current().pid());
        return R.ok(m);
    }

    @SaCheckPermission(PermConst.LOG_LIST)
    @GetMapping("/logs/placeholder")
    public R<Void> logsPlaceholder() {
        return R.ok(null);
    }
}