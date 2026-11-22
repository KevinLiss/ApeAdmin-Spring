package com.apeadmin.server.service;

import com.apeadmin.plugin.domain.PluginDO;
import com.apeadmin.plugin.mapper.PluginMapper;
import com.apeadmin.system.domain.SysUser;
import com.apeadmin.system.mapper.SysDeptMapper;
import com.apeadmin.system.mapper.SysMenuMapper;
import com.apeadmin.system.mapper.SysRoleMapper;
import com.apeadmin.system.mapper.SysUserMapper;
import com.apeadmin.mcp.McpAuditDO;
import com.apeadmin.mcp.McpToolDO;
import com.apeadmin.mcp.mapper.McpAuditMapper;
import com.apeadmin.mcp.mapper.McpToolMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OperatingSystem;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合服务（对齐 Python 版 /dashboard/stats、/dashboard/system 数据契约）：
 *
 * <pre>
 * stats  — 系统规模计数 + MCP 审计活动 + 近 14 天调用趋势 + 插件列表
 * system — OSHI 实时系统监控（CPU/内存/磁盘/网络）+ MCP 工具 + 24h 在线用户
 * </pre>
 *
 * <p>OSHI 采集与 psutil 语义对齐：CPU 用两次 ticks 差值采样；网络速率由服务级
 * 缓存计数与上一次请求做差（多次刷新页面即可得到平滑速率）。
 */
@Service
public class DashboardService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysDeptMapper deptMapper;
    private final McpToolMapper toolMapper;
    private final McpAuditMapper auditMapper;
    private final PluginMapper pluginMapper;

    // OSHI 单例（进程级复用）
    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final VirtualMemory swap;
    private final OperatingSystem os;
    private long[] prevTicks;
    /** 上次 CPU ticks 采样时间（ms）；0 表示尚未采样。 */
    private volatile long lastTicksTs;
    // 网络速率计算缓存：[lastSent, lastRecv, lastTs]
    private volatile long[] lastNet = new long[]{0, 0, 0};
    /** 主机名启动时解析一次。运行期反复调 InetAddress.getLocalHost() 会在 macOS 上
     * 周期性触发 5s 的 mDNS 解析超时（DNS 缓存过期后重解析 .local 域名），
     * 实测仪表盘接口每 ~15 次请求出现一次 5.3s 尖峰，均由此导致。 */
    private final String hostname;

    public DashboardService(SysUserMapper userMapper, SysRoleMapper roleMapper,
                            SysMenuMapper menuMapper, SysDeptMapper deptMapper,
                            McpToolMapper toolMapper, McpAuditMapper auditMapper,
                            PluginMapper pluginMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.deptMapper = deptMapper;
        this.toolMapper = toolMapper;
        this.auditMapper = auditMapper;
        this.pluginMapper = pluginMapper;
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        this.cpu = hal.getProcessor();
        this.memory = hal.getMemory();
        this.swap = hal.getMemory().getVirtualMemory();
        this.os = si.getOperatingSystem();
        this.prevTicks = cpu.getSystemCpuLoadTicks();
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "unknown";
        }
        this.hostname = h;
    }

    // ---------------- stats ----------------

    public Map<String, Object> stats(String username, String nickname) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("nickname", nickname);
        data.put("user", user);

        // ---- 系统规模 ----
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("user_total", userMapper.selectCount(null));
        stat.put("role_total", roleMapper.selectCount(null));
        stat.put("menu_total", menuMapper.selectCount(null));
        stat.put("dept_total", deptMapper.selectCount(null));
        stat.put("plugin_total", pluginMapper.selectCount(null));
        stat.put("plugin_enabled", pluginMapper.selectCount(
                Wrappers.<PluginDO>lambdaQuery().eq(PluginDO::getEnabled, 1)));
        data.put("stats", stat);

        // ---- MCP 审计活动 ----
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("total", auditMapper.selectCount(null));
        audit.put("today", auditMapper.selectCount(
                Wrappers.<McpAuditDO>query()
                        .apply("DATE(created_at) = {0}", LocalDate.now().toString())));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (McpAuditDO r : auditMapper.selectList(Wrappers.<McpAuditDO>lambdaQuery()
                .orderByDesc(McpAuditDO::getId).last("LIMIT 5"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("action_type", r.getActionType());
            m.put("target_name", r.getTargetName());
            m.put("username", r.getUsername());
            m.put("status", r.getStatus());
            m.put("created_at", r.getCreatedAt() == null ? null : DT.format(r.getCreatedAt()));
            recent.add(m);
        }
        audit.put("recent", recent);
        data.put("audit", audit);

        // ---- 近 14 天调用趋势 ----
        LocalDate start = LocalDate.now().minusDays(13);
        List<Map<String, Object>> rows = auditMapper.selectMaps(
                Wrappers.<McpAuditDO>query()
                        .select("DATE(created_at) as day", "COUNT(*) as cnt")
                        .apply("DATE(created_at) >= {0}", start.toString())
                        .groupBy("DATE(created_at)"));
        Map<String, Object> dayMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object day = row.get("day");
            Object cnt = row.get("cnt");
            if (day != null && cnt != null) {
                dayMap.put(String.valueOf(day), ((Number) cnt).longValue());
            }
        }
        List<String> dates = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            LocalDate d = start.plusDays(i);
            dates.add(d.format(DateTimeFormatter.ofPattern("MM-dd")));
            Object cnt = dayMap.get(d.toString());
            counts.add(cnt instanceof Number n ? n.longValue() : 0L);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dates", dates);
        trend.put("counts", counts);
        data.put("trend", trend);

        data.put("plugins", pluginBrief());
        return data;
    }

    // ---------------- system ----------------

    public Map<String, Object> system(String username, String nickname) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("nickname", nickname);
        data.put("user", user);

        data.putAll(collectSystemInfo());
        data.put("mcp_tools", mcpTools());
        data.put("online_users", onlineUsers());
        data.put("plugins", pluginBrief());
        return data;
    }

    /** OSHI 实时采集（字段对齐 Python 版 _collect_system_info）。 */
    private Map<String, Object> collectSystemInfo() {
        Map<String, Object> data = new LinkedHashMap<>();

        // CPU：两次 ticks 差值采样。轮询场景（前端 5s 一次）直接复用上次请求的
        // ticks（等价 psutil interval=None，窗口更长反而更平滑）；仅当上一次采样
        // 过旧（>10s，如冷启动首查）才短暂 sleep(250ms) 保证样本窗口。
        long nowTs = System.currentTimeMillis();
        if (lastTicksTs == 0 || nowTs - lastTicksTs > 10_000) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long[] ticks = cpu.getSystemCpuLoadTicks();
        double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(prevTicks);
        prevTicks = ticks;
        lastTicksTs = nowTs;
        double[] loadAvg = cpu.getSystemLoadAverage(3);
        Map<String, Object> cpuMap = new LinkedHashMap<>();
        cpuMap.put("percent", round1(cpuLoad * 100));
        cpuMap.put("cores_logical", cpu.getLogicalProcessorCount());
        cpuMap.put("cores_physical", cpu.getPhysicalProcessorCount());
        List<Double> load = new ArrayList<>();
        for (double v : loadAvg) {
            load.add(v < 0 ? 0.0 : round1(v));
        }
        cpuMap.put("load_avg", load);
        data.put("cpu", cpuMap);

        // 内存
        Map<String, Object> memMap = new LinkedHashMap<>();
        memMap.put("total", memory.getTotal());
        memMap.put("used", memory.getTotal() - memory.getAvailable());
        memMap.put("available", memory.getAvailable());
        memMap.put("percent", round1((memory.getTotal() - memory.getAvailable()) * 100.0 / memory.getTotal()));
        memMap.put("swap_total", swap.getSwapTotal());
        memMap.put("swap_used", swap.getSwapUsed());
        memMap.put("swap_percent", swap.getSwapTotal() == 0 ? 0.0
                : round1(swap.getSwapUsed() * 100.0 / swap.getSwapTotal()));
        data.put("memory", memMap);

        // 磁盘（根分区，psutil disk_usage('/') 语义）
        File root = new File("/");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        Map<String, Object> diskMap = new LinkedHashMap<>();
        diskMap.put("total", total);
        diskMap.put("used", total - free);
        diskMap.put("free", free);
        diskMap.put("percent", total == 0 ? 0.0 : round1((total - free) * 100.0 / total));
        data.put("disk", diskMap);

        // 网络：遍历非回环接口累加 + 差值速率
        long sent = 0, recv = 0;
        for (NetworkIF nif : new SystemInfo().getHardware().getNetworkIFs(false)) {
            try {
                nif.updateAttributes();
                if (nif.getBytesSent() > 0 || nif.getBytesRecv() > 0) {
                    sent += nif.getBytesSent();
                    recv += nif.getBytesRecv();
                }
            } catch (Exception ignore) {
                // 单网卡失效不阻断
            }
        }
        long netTs = System.currentTimeMillis();
        long[] last = lastNet;
        double sentRate = 0, recvRate = 0;
        if (last[2] > 0 && netTs > last[2] && sent >= last[0] && recv >= last[1]) {
            double sec = (netTs - last[2]) / 1000.0;
            sentRate = (sent - last[0]) / sec;
            recvRate = (recv - last[1]) / sec;
        }
        lastNet = new long[]{sent, recv, netTs};
        Map<String, Object> netMap = new LinkedHashMap<>();
        netMap.put("bytes_sent", sent);
        netMap.put("bytes_recv", recv);
        netMap.put("packets_sent", 0);
        netMap.put("packets_recv", 0);
        netMap.put("sent_rate", round1(sentRate));
        netMap.put("recv_rate", round1(recvRate));
        data.put("network", netMap);

        // 系统信息（主机名用启动时缓存，避免运行期 DNS 阻塞）
        Map<String, Object> sysMap = new LinkedHashMap<>();
        sysMap.put("hostname", hostname);
        sysMap.put("os", os.getFamily() + " " + os.getVersionInfo().getVersion());
        sysMap.put("uptime_seconds", os.getSystemUptime());
        sysMap.put("process_count", os.getProcessCount());
        data.put("system", sysMap);
        return data;
    }

    private List<Map<String, Object>> mcpTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpToolDO t : toolMapper.selectList(Wrappers.<McpToolDO>lambdaQuery()
                .eq(McpToolDO::getEnabled, 1).orderByAsc(McpToolDO::getId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            // 数据库中为 JSON 文本，需反序列化为数组，否则前端会把字符串按字符逐个渲染
            m.put("required_permissions", parsePermissions(t.getRequiredPermissions()));
            tools.add(m);
        }
        return tools;
    }

    /** 把 sys_mcp_tool.required_permissions 的 JSON 文本解析为列表（对齐 Python 版 list[str]）。 */
    private static List<String> parsePermissions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> onlineUsers() {
        String cutoff = LocalDateTime.now().minusHours(24).toString();
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .isNotNull(SysUser::getLastLoginAt)
                .gt(SysUser::getLastLoginAt, cutoff)
                .orderByDesc(SysUser::getLastLoginAt)
                .last("LIMIT 20"));
        List<Map<String, Object>> online = new ArrayList<>();
        for (SysUser u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("last_login_at", u.getLastLoginAt() == null ? null : friendly(u.getLastLoginAt()));
            m.put("last_login_ip", u.getLastLoginIp());
            online.add(m);
        }
        return online;
    }

    private List<Map<String, Object>> pluginBrief() {
        List<PluginDO> plugins = pluginMapper.selectList(Wrappers.<PluginDO>lambdaQuery()
                .orderByAsc(PluginDO::getId).last("LIMIT 8"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PluginDO p : plugins) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("display_name", p.getTitle());
            m.put("version", p.getVersion());
            m.put("enabled", p.getEnabled() != null && p.getEnabled() == 1);
            m.put("created_at", p.getCreatedAt() == null ? null
                    : p.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            list.add(m);
        }
        return list;
    }

    /** ISO 时间字符串 → "yyyy-MM-dd HH:mm:ss"（兼容秒/毫秒尾部）。 */
    private String friendly(String iso) {
        try {
            String s = iso.contains(".") ? iso.substring(0, iso.indexOf('.')) : iso;
            return s.replace('T', ' ');
        } catch (Exception e) {
            return iso;
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
