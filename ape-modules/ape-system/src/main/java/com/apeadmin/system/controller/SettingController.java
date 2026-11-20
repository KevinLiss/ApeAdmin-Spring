package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.domain.SysSetting;
import com.apeadmin.system.mapper.SysSettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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

    private final SysSettingMapper settingMapper;

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

    public record SaveBody(List<ItemReq> items) {
    }

    public record ItemReq(String key, String value, Integer is_public, String remark) {
    }
}
