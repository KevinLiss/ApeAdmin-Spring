package com.apeadmin.system.service;

import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.system.domain.SysFile;
import com.apeadmin.system.domain.SysFileFolder;
import com.apeadmin.system.mapper.SysFileFolderMapper;
import com.apeadmin.system.mapper.SysFileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 文件管理：上传/夹树/移动/重命名/删除/下载（对齐 Python files.py 核心语义）。 */
@Service
public class FileService {

    private final SysFileMapper fileMapper;
    private final SysFileFolderMapper folderMapper;

    @Value("${ape.upload-dir:./uploads}")
    private String uploadDir;

    public FileService(SysFileMapper fileMapper, SysFileFolderMapper folderMapper) {
        this.fileMapper = fileMapper;
        this.folderMapper = folderMapper;
    }

    // ---------------------------------------------------------------- 夹

    /** 夹树（含每夹文件数）。 */
    public List<Map<String, Object>> folderTree() {
        List<SysFileFolder> folders = folderMapper.selectList(
                Wrappers.<SysFileFolder>lambdaQuery().orderByAsc(SysFileFolder::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysFileFolder f : folders) {
            out.add(folderOut(f));
        }
        return out;
    }

    public Map<String, Object> createFolder(Long parentId, String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件夹名非法");
        }
        if (parentId == null) {
            parentId = 0L;
        }
        if (parentId != 0 && folderMapper.selectById(parentId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "父文件夹不存在");
        }
        Long dup = folderMapper.selectCount(Wrappers.<SysFileFolder>lambdaQuery()
                .eq(SysFileFolder::getParentId, parentId)
                .eq(SysFileFolder::getName, name));
        if (dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "同级文件夹已存在: " + name);
        }
        SysFileFolder f = new SysFileFolder();
        f.setParentId(parentId);
        f.setName(name.strip());
        folderMapper.insert(f);
        return folderOut(f);
    }

    public void renameFolder(Long id, String name) {
        SysFileFolder f = ownedFolder(id);
        if (name == null || name.isBlank() || name.contains("/") || name.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件夹名非法");
        }
        f.setName(name.strip());
        folderMapper.updateById(f);
    }

    /** 删除夹（递归删子夹与文件；含磁盘文件）。 */
    public void deleteFolder(Long id) {
        ownedFolder(id);
        List<Long> chain = new ArrayList<>();
        collectSubfolders(id, chain);
        chain.add(id);
        for (Long fid : chain) {
            List<SysFile> files = fileMapper.selectList(Wrappers.<SysFile>lambdaQuery()
                    .eq(SysFile::getFolderId, fid));
            for (SysFile f : files) {
                deleteDisk(f);
                fileMapper.deleteById(f.getId());
            }
            if (!fid.equals(id)) {
                folderMapper.deleteById(fid);
            }
        }
        folderMapper.deleteById(id);
    }

    /** 移动夹（防自引用/环）。 */
    public void moveFolder(Long id, Long targetParentId) {
        SysFileFolder f = ownedFolder(id);
        Long target = targetParentId == null ? 0L : targetParentId;
        if (target.equals(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能移动到自身");
        }
        if (target != 0) {
            if (folderMapper.selectById(target) == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "目标文件夹不存在");
            }
            List<Long> subChain = new ArrayList<>();
            collectSubfolders(id, subChain);
            if (subChain.contains(target)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能移动到自己的子文件夹");
            }
        }
        f.setParentId(target);
        folderMapper.updateById(f);
    }

    // ---------------------------------------------------------------- 文件

    /** 列表（folder_id 过滤 + 关键词 + 分页）。 */
    public Map<String, Object> list(Long folderId, String keyword, long page, long pageSize) {
        if (folderId == null) {
            folderId = 0L;
        }
        var q = Wrappers.<SysFile>lambdaQuery()
                .eq(SysFile::getFolderId, folderId)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(SysFile::getOriginName, keyword))
                .orderByDesc(SysFile::getId);
        long total = fileMapper.selectCount(q);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);  // 单页上限：防超大 pageSize 全表拉取
        List<SysFile> rows = fileMapper.selectList(q.last("LIMIT " + safePageSize + " OFFSET " + ((page - 1) * safePageSize)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysFile f : rows) {
            items.add(fileOut(f));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("items", items);
        return out;
    }

    /** 上传（multipart → 磁盘 UUID 名 + 元数据落库）。 */
    public Map<String, Object> upload(Long folderId, MultipartFile file, Long uploaderId) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择文件");
        }
        if (folderId == null || folderId == 0) {
            folderId = 0L;
        } else if (folderMapper.selectById(folderId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件夹不存在");
        }
        Path dir = Path.of(uploadDir);
        Files.createDirectories(dir);
        String origin = sanitizeName(file.getOriginalFilename());
        String ext = origin.contains(".") ? origin.substring(origin.lastIndexOf('.')) : "";
        String stored = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = dir.resolve(stored);
        String md5;
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        md5 = md5Hex(target);
        SysFile f = new SysFile();
        f.setFolderId(folderId);
        f.setOriginName(origin);
        f.setStoredName(stored);
        f.setMime(file.getContentType());
        f.setSize(target.toFile().length());
        f.setMd5(md5);
        f.setUploaderId(uploaderId);
        fileMapper.insert(f);
        return fileOut(f);
    }

    /** 文件磁盘路径（下载用）。 */
    public Path pathOf(Long id) {
        SysFile f = ownedFile(id);
        Path p = Path.of(uploadDir).resolve(f.getStoredName());
        if (!Files.exists(p)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件已丢失（磁盘无存储）");
        }
        return p;
    }

    /** 文件元数据（下载响应头等场景）。 */
    public Map<String, Object> metaOf(Long id) {
        return fileOut(ownedFile(id));
    }

    public void renameFile(Long id, String name) {
        SysFile f = ownedFile(id);
        if (name == null || name.isBlank() || name.contains("/") || name.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名非法");
        }
        f.setOriginName(sanitizeName(name));
        fileMapper.updateById(f);
    }

    public void moveFile(Long id, Long targetFolderId) {
        SysFile f = ownedFile(id);
        Long target = targetFolderId == null ? 0L : targetFolderId;
        if (target != 0 && folderMapper.selectById(target) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标文件夹不存在");
        }
        f.setFolderId(target);
        fileMapper.updateById(f);
    }

    public void deleteFile(Long id) {
        SysFile f = ownedFile(id);
        deleteDisk(f);
        fileMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 辅助

    private SysFileFolder ownedFolder(Long id) {
        SysFileFolder f = id == null ? null : folderMapper.selectById(id);
        if (f == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件夹不存在");
        }
        return f;
    }

    private SysFile ownedFile(Long id) {
        SysFile f = id == null ? null : fileMapper.selectById(id);
        if (f == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return f;
    }

    private void collectSubfolders(Long parentId, List<Long> acc) {
        List<SysFileFolder> subs = folderMapper.selectList(Wrappers.<SysFileFolder>lambdaQuery()
                .eq(SysFileFolder::getParentId, parentId));
        for (SysFileFolder s : subs) {
            acc.add(s.getId());
            collectSubfolders(s.getId(), acc);
        }
    }

    private void deleteDisk(SysFile f) {
        try {
            Files.deleteIfExists(Path.of(uploadDir).resolve(f.getStoredName()));
        } catch (Exception ignore) {
        }
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        return name.replace("/", "_").replace("\\", "_").replace("..", "_").strip();
    }

    private static String md5Hex(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> folderOut(SysFileFolder f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("parent_id", f.getParentId());
        m.put("name", f.getName());
        m.put("file_count", fileMapper.selectCount(Wrappers.<SysFile>lambdaQuery()
                .eq(SysFile::getFolderId, f.getId())));
        return m;
    }

    private Map<String, Object> fileOut(SysFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("folder_id", f.getFolderId());
        m.put("origin_name", f.getOriginName());
        m.put("stored_name", f.getStoredName());
        m.put("mime", f.getMime());
        m.put("size", f.getSize());
        m.put("md5", f.getMd5());
        m.put("uploader_id", f.getUploaderId());
        return m;
    }

    @SuppressWarnings("unused")
    private static LocalDateTime nowRef() {
        return LocalDateTime.now();
    }
}
