package com.apeadmin.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.common.constant.PermConst;
import com.apeadmin.chat.service.ProviderService;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** AI 供应商管理（对齐前端 /ai/providers 契约）。 */
@RestController
@RequestMapping("/api/v1/ai/providers")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @Data
    public static class ProviderReq {
        private String name;
        private String provider_type;
        private String base_url;
        private String api_key;
        private List<String> models;
        private Integer enabled;
        private Integer sort;
        private String remark;
        private String headers;
    }

    @SaCheckPermission(PermConst.PROVIDER_LIST)
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(name = "page_size", defaultValue = "20") long pageSize) {
        return R.ok(providerService.page(page, pageSize));
    }

    @SaCheckPermission(PermConst.PROVIDER_LIST)
    @GetMapping("/all")
    public R<List<Map<String, Object>>> listAll() {
        return R.ok(providerService.listEnabled());
    }

    @SaCheckPermission(PermConst.PROVIDER_ADD)
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody ProviderReq body) {
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "供应商名称不能为空");
        }
        if (body.getProvider_type() == null || body.getProvider_type().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "供应商类型 provider_type 不能为空");
        }
        Long id = providerService.create(body.getName(), body.getProvider_type(), body.getBase_url(),
                body.getApi_key(), body.getModels(), body.getEnabled(), body.getSort(), body.getRemark(),
                body.getHeaders());
        return R.ok(Map.of("id", id), "创建成功");
    }

    @SaCheckPermission(PermConst.PROVIDER_EDIT)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ProviderReq body) {
        providerService.update(id, body.getName(), body.getProvider_type(), body.getBase_url(),
                body.getApi_key(), body.getModels(), body.getEnabled(), body.getSort(), body.getRemark(),
                body.getHeaders());
        return R.ok(null, "更新成功");
    }

    @SaCheckPermission(PermConst.PROVIDER_DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return R.ok(null, "删除成功");
    }

    @SaCheckPermission(PermConst.PROVIDER_LIST)
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.ok(providerService.test(id), "测试完成");
    }
}
