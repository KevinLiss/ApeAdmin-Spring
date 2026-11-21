package com.apeadmin.server.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.security.UserContextHolder;
import com.apeadmin.server.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仪表盘端点（对齐前端契约，复用 Python 版响应字段）：
 *
 * <pre>
 * GET /dashboard/stats  — 系统规模 + MCP 审计活动 + 14 天趋势 + 插件列表
 * GET /dashboard/system — OSHI 系统监控 + MCP 工具 + 24h 在线用户
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @SaCheckPermission(PermConst.DASHBOARD_VIEW)
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        var user = UserContextHolder.require();
        return R.ok(dashboardService.stats(user.username(), user.nickname()));
    }

    @SaCheckPermission(PermConst.DASHBOARD_VIEW)
    @GetMapping("/system")
    public R<Map<String, Object>> system() {
        var user = UserContextHolder.require();
        return R.ok(dashboardService.system(user.username(), user.nickname()));
    }
}
