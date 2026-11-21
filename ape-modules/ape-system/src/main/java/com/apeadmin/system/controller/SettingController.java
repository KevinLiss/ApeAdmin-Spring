package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.framework.log.OperateLog;
import com.apeadmin.system.domain.SysSetting;
import com.apeadmin.system.mapper.SysSettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
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
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置：/settings/public 免登录（登录页取站点名/Logo）+ 管理端全量读写 + /settings/version。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingController {

    private static final String APP_VERSION = "1.0.0-SNAPSHOT";

    private static final long MAX_BRAND_IMAGE_SIZE = 2L * 1024 * 1024; // 2MB
    private static final List<String> BRAND_IMAGE_EXTENSIONS =
            List.of("png", "jpg", "jpeg", "gif", "webp", "svg", "ico");

    private final SysSettingMapper settingMapper;

    @Value("${ape.upload-dir:./uploads}")
    private String uploadDir;

    public SettingController(SysSettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    public record SettingResp(Long id, String key, String value, Integer is_public, String remark) {
    }

    @SaIgnore
    @GetMapping("/public")
    public R<List<SettingResp>> publicSettings() {
        List<SettingResp> items = settingMapper.selectList(Wrappers.<SysSetting>lambdaQuery()
                        .eq(SysSetting::getIsPublic, 1)).stream()
                .map(s -> new SettingResp(s.getId(), s.getKey(), s.getValue(), s.getIsPublic(), s.getRemark()))
                .toList();
        return R.ok(items);
    }

    @SaCheckPermission(PermConst.SETTING_LIST)
    @GetMapping
    public R<List<SettingResp>> all() {
        List<SettingResp> items = settingMapper.selectList(Wrappers.<SysSetting>lambdaQuery()).stream()
                .map(s -> new SettingResp(s.getId(), s.getKey(), s.getValue(), s.getIsPublic(), s.getRemark()))
                .toList();
        return R.ok(items);
    }

    /** 批量保存：{items:[{key,value,is_public,remark}]}（upsert）。 */
    @SaCheckPermission(PermConst.SETTING_EDIT)
    @PutMapping
    public R<Void> save(@RequestBody SaveBody body) {
        if (body == null || body.items() == null || body.items().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无可保存项");
        }
        List<SysSetting> toSave = new ArrayList<>();
        for (ItemReq it : body.items()) {
            if (it.key() == null || it.key().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "key 必填");
            }
            List<SysSetting> existing = settingMapper.selectList(Wrappers.<SysSetting>lambdaQuery()
                    .eq(SysSetting::getKey, it.key()));
            SysSetting s = existing.isEmpty() ? new SysSetting() : existing.get(0);
            s.setKey(it.key());
            s.setValue(it.value());
            if (it.is_public() != null) {
                s.setIsPublic(it.is_public());
            }
            if (it.remark() != null) {
                s.setRemark(it.remark());
            }
            toSave.add(s);
        }
        for (SysSetting s : toSave) {
            if (s.getId() == null) {
                settingMapper.insert(s);
            } else {
                settingMapper.updateById(s);
            }
        }
        return R.ok(null, "保存成功");
    }

    /** 版本信息（前端设置页“版本信息”卡片）。 */
    @SaCheckPermission(PermConst.VERSION_VIEW)
    @GetMapping("/version")
    public R<Map<String, Object>> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "apeadmin-spring");
        m.put("version", APP_VERSION);
        m.put("build", "m1-m4");
        m.put("db", "sqlite(wal)");
        return R.ok(m);
    }

    /** 单项保存：PUT /settings/{key}，body={value:...}（前端“快速改单项”调用）。 */
    @OperateLog(action = "更新单项设置", module = "系统设置")
    @SaCheckPermission(PermConst.SETTING_EDIT)
    @PutMapping("/{key}")
    public R<Void> saveOne(@PathVariable String key, @RequestBody Map<String, Object> body) {
        Object value = body == null ? null : body.get("value");
        List<SysSetting> existing = settingMapper.selectList(Wrappers.<SysSetting>lambdaQuery()
                .eq(SysSetting::getKey, key));
        if (existing.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "设置项不存在：" + key);
        }
        SysSetting s = existing.get(0);
        s.setValue(value == null ? null : String.valueOf(value));
        settingMapper.updateById(s);
        return R.ok(null, "已保存 " + key);
    }

    /**
     * 品牌图上传（登录页 Logo / 背景）：存 uploads/brand/，
     * 返回 /uploads/brand/&lt;file&gt;（WebStaticConfig 已挂载 /uploads/** 为公开静态资源）。
     */
    @OperateLog(action = "上传品牌图", module = "系统设置")
    @SaCheckPermission(PermConst.SETTING_EDIT)
    @PostMapping("/brand-image")
    public R<Map<String, Object>> uploadBrandImage(@RequestParam("file") MultipartFile file) {
        String original = (file.getOriginalFilename() == null ? "" : file.getOriginalFilename()).trim();
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase();
        if (!BRAND_IMAGE_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持图片格式：" + BRAND_IMAGE_EXTENSIONS);
        }
        if (file.getSize() > MAX_BRAND_IMAGE_SIZE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片大小不能超过 2MB");
        }
        try {
            // MultipartFile.transferTo 只接受绝对路径（相对路径会被解析到 Tomcat 临时目录）
            Path brandDir = Path.of(uploadDir, "brand").toAbsolutePath().normalize();
            Files.createDirectories(brandDir);
            String stamp = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            byte[] salt = new byte[6];
            new SecureRandom().nextBytes(salt);
            String filename = stamp + "_" + HexFormat.of().formatHex(salt) + "." + ext;
            file.transferTo(brandDir.resolve(filename).toFile());
            return R.ok(Map.of("url", "/uploads/brand/" + filename), "上传成功");
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "品牌图保存失败：" + e.getMessage());
        }
    }

    public record SaveBody(List<ItemReq> items) {
    }

    public record ItemReq(String key, String value, Integer is_public, String remark) {
    }
}
