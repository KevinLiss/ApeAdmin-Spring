package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 系统更新端点（对齐前端 /system/version、/system/check-update、/system/update 契约）：
 *
 * <pre>
 * GET  /system/version      — 当前版本 + 运行信息（设置页“版本信息”卡片）
 * GET  /system/check-update — 对比 ApeHub 最新发布（网络失败降级 reachable=false）
 * POST /system/update       — 上传部署包（ZIP）→ SHA256 → 备份 → 原子替换 → 约定退出码重启
 * </pre>
 *
 * <p>update 的 Java 化实现：ZIP 包根含一个顶层目录（内为 apeadmin.jar + 可选前端产物），
 * 校验后备份当前 jar 为 backup_{ts}.jar，替换新 jar，System.exit(restartExitCode) 交由
 * 外部守护拉起。演示环境（mvn spring-boot:run）无守护进程——退出码配置为 -1 时禁用自动重启。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_PACKAGE_BYTES = 200L * 1024 * 1024;   // 200MB

    @Value("${ape.app.version:1.0.0-SNAPSHOT}")
    private String appVersion;

    @Value("${ape.apehub.base:https://apehub.finecv.cn}")
    private String apehubBase;

    @Value("${ape.plugin.restart-exit-code:90}")
    private int restartExitCode;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    @SaCheckPermission(PermConst.VERSION_VIEW)
    @GetMapping("/version")
    public R<Map<String, Object>> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current_version", appVersion);
        m.put("app_name", "apeadmin-spring");
        m.put("java_version", System.getProperty("java.version"));
        m.put("project_root", Path.of("").toAbsolutePath().toString());
        m.put("has_env", Files.exists(Path.of(".env")));
        m.put("pid", ProcessHandle.current().pid());
        // 底座标识：java（Spring Boot）；前端用此校验部署包匹配
        m.put("runtime", "java");
        return R.ok(m);
    }

    /** 对比 ApeHub 最新发布（失败降级，不抛错）。 */
    @SaCheckPermission(PermConst.VERSION_VIEW)
    @GetMapping("/check-update")
    public R<Map<String, Object>> checkUpdate() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current_version", appVersion);
        result.put("reachable", false);
        result.put("has_update", false);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apehubBase.replaceAll("/$", "")
                            + "/api/v1/apehub-web/site/public/releases?framework=java"))
                    .timeout(Duration.ofSeconds(6))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(resp.body());
            JsonNode items = body.path("data").path("items");
            if (items.isArray() && !items.isEmpty()) {
                JsonNode latest = items.get(0);
                String latestVersion = latest.path("version").asText("");
                result.put("reachable", true);
                result.put("latest_version", latestVersion);
                result.put("release_id", latest.path("id").asLong());
                result.put("title", latest.path("title").asText(null));
                result.put("changelog", latest.path("changelog").asText(null));
                result.put("published_at", latest.path("published_at").asText(null));
                result.put("download_url", latest.path("id").asLong() > 0
                        ? apehubBase.replaceAll("/$", "")
                                + "/api/v1/apehub-web/site/public/releases/"
                                + latest.path("id").asLong() + "/download"
                        : null);
                result.put("has_update", compareSemver(latestVersion, appVersion) > 0);
            }
        } catch (Exception e) {
            log.warn("check-update 失败（降级为不可达）: {}", e.getMessage());
        }
        return R.ok(result);
    }

    /** 上传部署包并就地更新（ZIP：顶层目录内含 apeadmin.jar）。 */
    @SaCheckPermission(PermConst.VERSION_UPDATE)
    @PostMapping("/update")
    public R<Map<String, Object>> update(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请上传部署包 ZIP");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".zip")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 .zip 部署包");
        }
        if (file.getSize() > MAX_PACKAGE_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部署包超过 200MB 上限");
        }

        Path temp = Files.createTempFile("apeadmin-update-", ".zip");
        try {
            file.transferTo(temp);
            // ZIP 魔数校验（PK\x03\x04）：非 ZIP 直接 400，而非打开时抛通用异常
            try (InputStream head = Files.newInputStream(temp)) {
                byte[] magic = head.readNBytes(4);
                if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K'
                        || magic[2] != 3 || magic[3] != 4) {
                    throw new IllegalArgumentException("文件不是合法的 ZIP 部署包");
                }
            }
            String sha256 = sha256Hex(temp);
            String rootDir = validatePackage(temp);

            // 备份当前 jar（运行位置为 target/ 或当前目录的 apeadmin.jar）
            Path currentJar = locateCurrentJar();
            Path backup = currentJar.getParent()
                    .resolve("backup_" + System.currentTimeMillis() + ".jar");
            Files.copy(currentJar, backup, StandardCopyOption.REPLACE_EXISTING);

            // 提取新 jar 并原子替换
            Path newJar = extractJar(temp, rootDir);
            Files.copy(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);

            log.info("部署包更新完成：{} sha256={}，备份 {}，等待守护拉起重启",
                    filename, sha256.substring(0, 16), backup.getFileName());

            // 约定退出码退出（外部守护拉起）；演示环境配 -1 禁用
            if (restartExitCode >= 0) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(800);   // 留时间把响应写给客户端
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(restartExitCode);
                });
            }
            return R.ok(Map.of(
                    "sha256", sha256,
                    "backup", backup.getFileName().toString(),
                    "restarted", restartExitCode >= 0), "更新完成");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignore) {
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 校验包结构：必须单一顶层目录且内含 apeadmin.jar；返回顶层目录名。 */
    private String validatePackage(Path zip) throws Exception {
        String root = null;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith("/") || n.contains("..")) {
                    throw new IllegalArgumentException("非法条目路径: " + n);
                }
                String top = n.split("/")[0];
                if (top.isBlank()) {
                    continue;
                }
                if (root == null) {
                    root = top;
                } else if (!root.equals(top)) {
                    throw new IllegalArgumentException("包必须只含一个顶层目录（发现多个: " + root + ", " + top + "）");
                }
            }
            if (root == null || zf.getEntry(root + "/apeadmin.jar") == null) {
                throw new IllegalArgumentException("部署包缺 " + (root == null ? "apeadmin.jar" : root + "/apeadmin.jar"));
            }
        }
        return root;
    }

    private Path extractJar(Path zip, String rootDir) throws Exception {
        Path out = Files.createTempFile("apeadmin-new-", ".jar");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry(rootDir + "/apeadmin.jar");
            try (InputStream in = zf.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return out;
    }

    private Path locateCurrentJar() {
        // 运行形态：java -jar apeadmin.jar（工作目录）或 mvn spring-boot:run（target/）
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve("apeadmin.jar");
        if (Files.exists(direct)) {
            return direct;
        }
        Path target = cwd.resolve("ape-server").resolve("target").resolve("apeadmin.jar");
        if (Files.exists(target)) {
            return target;
        }
        throw new IllegalArgumentException("未定位到运行中的 apeadmin.jar（更新仅支持 jar 部署形态）");
    }

    private static int compareSemver(String a, String b) {
        long[] va = semver(a);
        long[] vb = semver(b);
        for (int i = 0; i < 3; i++) {
            int cmp = Long.compare(va[i], vb[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static long[] semver(String v) {
        if (v == null || v.isBlank()) {
            return new long[]{0, 0, 0};
        }
        String[] parts = v.replaceFirst("^v", "").split("[-+.]", 3);
        long[] out = new long[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException ignore) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String sha256Hex(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
