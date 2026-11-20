package com.apeadmin.plugin.loader;

import com.apeadmin.plugin.ApePlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * L2 ZIP 插件加载器（白名单校验 + URLClassLoader 动态装配）。
 *
 * <p>ZIP 结构约定（对齐 Python 版白名单思路，Java 化为 jar 形态）：
 * <pre>
 * plugin.zip
 * ├── plugin.json          # 必需：name/display_name/version/description/author
 * └── plugin.jar           # 必需：含 ApePlugin 实现类（无则报错）
 * </pre>
 *
 * <p>白名单规则：仅允许 plugin.json + *.jar 两种条目；禁止目录穿越
 * （entry 名含 .. 或以 / 开头的绝对路径）、符号链接、超过 10MB。
 * 加载后经 URLClassLoader 反射实例化实现类（L2 插件提供 MCP 工具/事件，
 * 不动态注册 HTTP 路由——Spring MVC 运行期注册不在本版范围）。
 */
@Slf4j
@Component
public class ZipPluginLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_ZIP_BYTES = 10 * 1024 * 1024;

    @Value("${ape.plugin.dir:./plugins}")
    private String pluginDir;

    /** 校验通过的 ZIP → (manifest, 实例化完成的插件)。落盘到 plugins/{name}/。 */
    public ApePlugin install(Path zipPath) throws IOException {
        validate(zipPath);

        JsonNode manifest = readManifest(zipPath);
        String name = manifest.path("name").asText("").trim().toLowerCase();
        if (!name.matches("[a-z][a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("插件名非法（snake_case，2-41 位）: " + name);
        }

        Path targetDir = Path.of(pluginDir).resolve(name);
        Files.createDirectories(targetDir);
        extract(zipPath, targetDir);   // 白名单内解压（覆盖更新）

        return loadFromDirectory(targetDir);
    }

    /** 从 plugins/{name}/ 目录加载（重启恢复路径）。 */
    public ApePlugin loadFromDirectory(Path dir) throws IOException {
        Path jar = dir.resolve("plugin.jar");
        if (!Files.exists(jar)) {
            throw new IllegalArgumentException("插件目录缺 plugin.jar: " + dir);
        }
        JsonNode manifest = JSON.readTree(Files.readAllBytes(dir.resolve("plugin.json")));

        try (URLClassLoader cl = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader())) {
            // 扫描 jar：找 ApePlugin 实现类（manifest 可指定 main_class，否则自动扫描）
            String mainClass = manifest.path("main_class").asText("");
            String impl = mainClass.isBlank() ? scanImpl(cl, jar) : mainClass;
            if (impl == null) {
                throw new IllegalArgumentException("plugin.jar 中未找到 ApePlugin 实现类");
            }
            Class<?> clazz = cl.loadClass(impl);
            if (!ApePlugin.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(impl + " 未实现 ApePlugin 接口");
            }
            return (ApePlugin) clazz.getDeclaredConstructor().newInstance();
        } catch (IOException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("插件实例化失败: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 校验与解压

    private void validate(Path zip) throws IOException {
        if (!Files.exists(zip)) {
            throw new IllegalArgumentException("ZIP 不存在");
        }
        if (Files.size(zip) > MAX_ZIP_BYTES) {
            throw new IllegalArgumentException("ZIP 超过 10MB 上限");
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (zf.getEntry("plugin.json") == null) {
                throw new IllegalArgumentException("ZIP 缺 plugin.json（清单必需）");
            }
            if (zf.getEntry("plugin.jar") == null) {
                throw new IllegalArgumentException("ZIP 缺 plugin.jar（主程序必需）");
            }
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith("/") || n.contains("..")) {
                    throw new IllegalArgumentException("非法条目路径: " + n);
                }
                boolean ok = switch (n) {
                    case "plugin.json", "plugin.jar" -> true;
                    default -> false;
                };
                if (!ok) {
                    throw new IllegalArgumentException("白名单外条目（仅允许 plugin.json/plugin.jar）: " + n);
                }
            }
        }
    }

    private JsonNode readManifest(Path zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return JSON.readTree(zf.getInputStream(zf.getEntry("plugin.json")));
        }
    }

    private void extract(Path zip, Path target) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (String entryName : List.of("plugin.json", "plugin.jar")) {
                ZipEntry e = zf.getEntry(entryName);
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target.resolve(entryName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String scanImpl(URLClassLoader cl, Path jar) throws IOException {
        List<String> candidates = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (n.endsWith(".class") && !n.contains("$")) {
                    String fqn = n.replace('/', '.').substring(0, n.length() - ".class".length());
                    try {
                        Class<?> c = cl.loadClass(fqn);
                        if (ApePlugin.class.isAssignableFrom(c) && !c.isInterface()) {
                            candidates.add(fqn);
                        }
                    } catch (Throwable ignore) {
                        // 依赖缺失的类跳过
                    }
                }
            }
        }
        if (candidates.size() > 1) {
            log.warn("多个 ApePlugin 实现类 {}，取首个（可在 plugin.json 指定 main_class）", candidates);
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
