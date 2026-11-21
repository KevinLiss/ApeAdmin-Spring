package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 素材库浏览（对齐 Python 版 /files/assets/* 契约）：对物理目录分组的只读浏览 + 单文件删除。
 *
 * <pre>
 * GET    /files/assets/groups    — 分组列表（含 file_count/total_size 统计）
 * GET    /files/assets/list      — 组内子目录与文件列表（防目录穿越）
 * GET    /files/assets/download  — 文件下载/预览（preview=1 inline）
 * DELETE /files/assets           — 删除单个文件（仅文件，向上清理空目录）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/files/assets")
public class AssetController {

    /** 分组定义：key → [名称, 相对 uploads 根的子路径, 风险, 说明]。 */
    private static final Map<String, String[]> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("uploads", new String[]{"上传文件", "", "medium", "文件管理页上传的原始文件"});
        GROUPS.put("brand", new String[]{"品牌图片", "brand", "low", "登录页 Logo / 背景，删除后需重新上传"});
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Value("${ape.upload-dir:./uploads}")
    private String uploadDir;

    @SaCheckPermission(PermConst.FILE_LIST)
    @GetMapping("/groups")
    public R<List<Map<String, Object>>> groups() {
        List<Map<String, Object>> data = new ArrayList<>();
        for (var e : GROUPS.entrySet()) {
            Path root = groupRoot(e.getKey());
            long count = 0;
            long size = 0;
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path p : walk.filter(Files::isRegularFile).toList()) {
                        count++;
                        try {
                            size += Files.size(p);
                        } catch (IOException ignore) {
                            // 统计失败不阻断
                        }
                    }
                } catch (IOException ignore) {
                    // 目录不可读按空处理
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("name", e.getValue()[0]);
            m.put("risk", e.getValue()[2]);
            m.put("note", e.getValue()[3]);
            m.put("exists", Files.isDirectory(root));
            m.put("file_count", count);
            m.put("total_size", size);
            data.add(m);
        }
        return R.ok(data);
    }

    @SaCheckPermission(PermConst.FILE_LIST)
    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam("group") String group,
                                       @RequestParam(defaultValue = "") String path) {
        Path root = groupRoot(group);
        if (!Files.isDirectory(root)) {
            return R.ok(Map.of("dirs", List.of(), "files", List.of()));
        }
        Path target = safePath(group, path);
        if (!Files.isDirectory(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "目录不存在");
        }
        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(target)) {
            for (Path p : entries.sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("path", rel);
                    d.put("name", p.getFileName().toString());
                    dirs.add(d);
                } else if (Files.isRegularFile(p)) {
                    files.add(fileDict(p, root));
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "目录读取失败：" + e.getMessage());
        }
        files.sort(Comparator.comparing(f -> String.valueOf(f.get("name"))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dirs", dirs);
        data.put("files", files);
        return R.ok(data);
    }

    @cn.dev33.satoken.annotation.SaCheckLogin
    @GetMapping("/download")
    public ResponseEntity<FileSystemResource> download(@RequestParam("group") String group,
                                                       @RequestParam("path") String path,
                                                       @RequestParam(defaultValue = "0") int preview) {
        Path target = safePath(group, path);
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        String name = target.getFileName().toString();
        String suffix = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        switch (suffix) {
            case "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" ->
                    type = MediaType.parseMediaType("image/" + ("jpg".equals(suffix) ? "jpeg" : suffix));
            case "pdf" -> type = MediaType.APPLICATION_PDF;
            case "txt", "md", "csv", "json", "log", "xml", "yml", "yaml" ->
                    type = MediaType.parseMediaType("text/plain; charset=utf-8");
            default -> { /* 保持 octet-stream */ }
        }
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        (preview == 1 ? "inline" : "attachment") + "; filename=\"" + name + "\"")
                .contentType(type)
                .body(new FileSystemResource(target));
    }

    @OperateLog(action = "删除素材文件", module = "文件管理")
    @SaCheckPermission(PermConst.FILE_DELETE)
    @DeleteMapping
    public R<Void> delete(@RequestParam("group") String group,
                          @RequestParam("path") String path) {
        Path target = safePath(group, path);
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        try {
            Files.delete(target);
            // 向上清理空目录（最多 2 层，保留分组根）
            Path root = groupRoot(group).toAbsolutePath().normalize();
            Path parent = target.getParent();
            for (int i = 0; i < 2 && parent != null; i++) {
                if (parent.equals(root)) {
                    break;
                }
                try (Stream<Path> list = Files.list(parent)) {
                    if (list.findAny().isPresent()) {
                        break;
                    }
                }
                Files.deleteIfExists(parent);
                parent = parent.getParent();
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "删除失败：" + e.getMessage());
        }
        return R.ok(null, "删除成功");
    }

    // ---------------- 内部 ----------------

    private Path groupRoot(String group) {
        String[] conf = GROUPS.get(group);
        if (conf == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "素材分组不存在：" + group);
        }
        Path base = Path.of(uploadDir).toAbsolutePath().normalize();
        return conf[1].isBlank() ? base : base.resolve(conf[1]).normalize();
    }

    /** 防目录穿越：解析后的目标必须位于组根内。 */
    private Path safePath(String group, String relative) {
        if (relative == null) {
            relative = "";
        }
        Path root = groupRoot(group).toAbsolutePath().normalize();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new BizException(ErrorCode.NOT_FOUND, "非法路径");
        }
        return candidate;
    }

    private Map<String, Object> fileDict(Path p, Path root) throws IOException {
        var stat = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
        String rel = root.relativize(p).toString().replace('\\', '/');
        String name = p.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel);
        m.put("name", name);
        m.put("extension", ext);
        m.put("size", stat.size());
        m.put("modified_at", ISO.format(Instant.ofEpochMilli(stat.lastModifiedTime().toMillis())));
        return m;
    }
}
