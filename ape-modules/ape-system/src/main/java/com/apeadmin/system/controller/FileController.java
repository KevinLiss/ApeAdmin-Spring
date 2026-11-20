package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.security.UserContextHolder;
import com.apeadmin.system.service.FileService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件管理端点（对齐前端 /files 契约）：
 *
 * <pre>
 * GET    /files/folders/tree          — 夹树
 * POST   /files/folders               — 新建夹
 * PUT    /files/folders/{id}          — 重命名夹
 * DELETE /files/folders/{id}         — 删除夹（递归）
 * POST   /files/folders/{id}/move     — 移动夹
 * GET    /files?folder_id=&keyword=   — 文件列表（分页）
 * POST   /files/upload?folder_id=     — 上传（multipart）
 * GET    /files/{id}/download         — 下载（流）
 * PUT    /files/{id}                  — 重命名文件
 * POST   /files/{id}/move             — 移动文件
 * DELETE /files/{id}                  — 删除文件
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Data
    public static class FolderReq {
        private String name;
        private Long parent_id;
    }

    @Data
    public static class MoveReq {
        private Long folder_id;
    }

    // ---------------------------------------------------------------- 夹

    @SaCheckPermission(PermConst.FILE_LIST)
    @GetMapping("/folders/tree")
    public R<List<Map<String, Object>>> folderTree() {
        return R.ok(fileService.folderTree());
    }

    @SaCheckPermission(PermConst.FILE_CREATE_FOLDER)
    @PostMapping("/folders")
    public R<Map<String, Object>> createFolder(@RequestBody FolderReq body) {
        return R.ok(fileService.createFolder(body.getParent_id(), body.getName()), "创建成功");
    }

    @SaCheckPermission(PermConst.FILE_RENAME)
    @PutMapping("/folders/{id}")
    public R<Void> renameFolder(@PathVariable Long id, @RequestBody FolderReq body) {
        fileService.renameFolder(id, body.getName());
        return R.ok(null, "重命名成功");
    }

    @SaCheckPermission(PermConst.FILE_DELETE)
    @DeleteMapping("/folders/{id}")
    public R<Void> deleteFolder(@PathVariable Long id) {
        fileService.deleteFolder(id);
        return R.ok(null, "删除成功");
    }

    @SaCheckPermission(PermConst.FILE_MOVE)
    @PostMapping("/folders/{id}/move")
    public R<Void> moveFolder(@PathVariable Long id, @RequestBody MoveReq body) {
        fileService.moveFolder(id, body.getFolder_id());
        return R.ok(null, "移动成功");
    }

    // ---------------------------------------------------------------- 文件

    @SaCheckPermission(PermConst.FILE_LIST)
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(name = "folder_id", defaultValue = "0") Long folderId,
                                        @RequestParam(defaultValue = "") String keyword,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        return R.ok(fileService.list(folderId, keyword, page, pageSize));
    }

    @SaCheckPermission(PermConst.FILE_UPLOAD)
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam(name = "folder_id", defaultValue = "0") Long folderId,
                                         @RequestParam("file") MultipartFile file) throws Exception {
        return R.ok(fileService.upload(folderId, file, UserContextHolder.require().id()), "上传成功");
    }

    @SaCheckPermission(PermConst.FILE_DOWNLOAD)
    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        Path path = fileService.pathOf(id);
        Map<String, Object> meta = fileService.metaOf(id);
        String origin = String.valueOf(meta.getOrDefault("origin_name", "file"));
        String encoded = URLEncoder.encode(origin, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    @SaCheckPermission(PermConst.FILE_RENAME)
    @PutMapping("/{id}")
    public R<Void> renameFile(@PathVariable Long id, @RequestBody FolderReq body) {
        fileService.renameFile(id, body.getName());
        return R.ok(null, "重命名成功");
    }

    @SaCheckPermission(PermConst.FILE_MOVE)
    @PostMapping("/{id}/move")
    public R<Void> moveFile(@PathVariable Long id, @RequestBody MoveReq body) {
        fileService.moveFile(id, body.getFolder_id());
        return R.ok(null, "移动成功");
    }

    @SaCheckPermission(PermConst.FILE_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> deleteFile(@PathVariable Long id) {
        fileService.deleteFile(id);
        return R.ok(null, "删除成功");
    }
}
