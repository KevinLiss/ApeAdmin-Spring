package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查端点。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @RequestMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "up", "app", "apeadmin-spring", "version", "1.0.0-SNAPSHOT");
    }

    @SaCheckPermission(PermConst.LOG_LIST)
    @GetMapping("/logs/placeholder")
    public R<Void> logsPlaceholder() {
        return R.ok(null);
    }
}
