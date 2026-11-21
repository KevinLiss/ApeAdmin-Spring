package com.apeadmin.plugin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.plugin.service.PluginManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 插件管理端点（对齐前端 /plugins 契约）：
 *
 * <pre>
 * GET    /plugins                — 分页列表（含 runtime_state）
 * PUT    /plugins/{id}/toggle    — 启用/停用
 * GET    /plugins/{id}/config    — 读配置
 * PUT    /plugins/{id}/config    — 写配置（L3 为 command/args/env）
 * POST   /plugins/upload         — L2 ZIP 安装（白名单校验）
 * POST   /plugins/restart       — 重启后端（退出码 90，交由外部守护拉起）
 * DELETE /plugins/{id}           — 删除插件
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginManager manager;

    /** 重启退出码约定（外部守护脚本按此拉起；禁用重启置 -1）。 */
    @Value("${ape.plugin.restart-exit-code:90}")
    private int restartExitCode;

    @SaCheckPermission(PermConst.PLUGIN_LIST)
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        return R.ok(manager.page(page, pageSize));
    }

    @SaCheckPermission(PermConst.PLUGIN_TOGGLE)
    @PutMapping("/{id}/toggle")
    public R<Map<String, Object>> toggle(@PathVariable Long id) {
        String name = manager.nameOf(id);
        try {
            Map<String, Object> result = manager.isEnabled(id) ? manager.disable(name) : manager.enable(name);
            return R.ok(result, manager.isEnabled(id) ? "插件已启用" : "插件已停用");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "插件切换失败: " + e.getMessage());
        }
    }

    @SaCheckPermission(PermConst.PLUGIN_CONFIG)
    @GetMapping("/{id}/config")
    public R<Object> getConfig(@PathVariable Long id) {
        return R.ok(manager.configOf(manager.nameOf(id)));
    }

    @SaCheckPermission(PermConst.PLUGIN_CONFIG)
    @PutMapping("/{id}/config")
    public R<Void> setConfig(@PathVariable Long id, @RequestBody(required = false) Object config) {
        try {
            manager.saveConfig(manager.nameOf(id), config);
            return R.ok(null, "配置已保存（重启或重新启用后生效）");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    @SaCheckPermission(PermConst.PLUGIN_UPLOAD)
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请上传 ZIP 文件");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("apeadmin-plugin-", ".zip");
            file.transferTo(temp);
            Map<String, Object> result = manager.installZip(temp);
            return R.ok(result, "插件安装成功（未启用）");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "插件安装失败: " + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** L3 外挂注册（stdio MCP server：command/args/env）。 */
    @SaCheckPermission(PermConst.PLUGIN_UPLOAD)
    @PostMapping("/l3")
    public R<Map<String, Object>> registerL3(@RequestBody L3Body body) {
        try {
            return R.ok(manager.registerL3(body.getName(), body.getDisplay_name(),
                    body.getDescription(), body.getConfig()), "外挂已注册（未启用）");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.CONFLICT, e.getMessage());
        }
    }

    @Data
    public static class L3Body {
        private String name;
        private String display_name;
        private String description;
        private Object config;
    }

    @SaCheckPermission(PermConst.PLUGIN_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        try {
            manager.delete(manager.nameOf(id));
            return R.ok(null, "插件已删除");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 重启后端：以约定退出码退出进程，由外部守护（systemd / Docker / 脚本循环）拉起。
     * 演示环境（mvn spring-boot:run）无守护——exitCode 配 -1 可禁用。
     */
    @SaCheckPermission(PermConst.PLUGIN_RESTART)
    @PostMapping("/restart")
    public R<Map<String, Object>> restart() {
        if (restartExitCode < 0) {
            return R.ok(Map.of("restarted", false), "重启未启用（ape.plugin.restart-exit-code = -1）");
        }
        log.info("收到重启请求，进程将退出（exitCode={}），等待外部守护拉起", restartExitCode);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);   // 留时间把响应写给客户端
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            System.exit(restartExitCode);
        });
        return R.ok(Map.of(
                "restarted", true,
                // 前端重启等待（pollBackendHealth）依赖 old_pid 判定新进程拉起
                "old_pid", ProcessHandle.current().pid()), "正在重启");
    }

    @SaCheckLogin
    @GetMapping("/events")
    public R<Object> eventsStub() {
        // 前端插件页预留（事件流待 M4 仪表盘一并做）
        return R.ok(Map.of());
    }

    @Data
    public static class ConfigBody {
        private Object config;
    }
}
