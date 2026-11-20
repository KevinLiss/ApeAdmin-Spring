package com.apeadmin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.system.domain.SysSetting;
import com.apeadmin.system.mapper.SysSettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置（/settings/public 免登录——登录页取站点名/Logo）。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingController {

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
}
