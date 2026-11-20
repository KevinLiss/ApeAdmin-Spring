package com.apeadmin.chat.service;

import com.apeadmin.chat.crypto.CryptoUtils;
import com.apeadmin.chat.domain.AiProviderDO;
import com.apeadmin.chat.llm.OpenAiCompatClient;
import com.apeadmin.chat.mapper.AiProviderMapper;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider 管理：CRUD + 连通性测试（对齐 Python 版 ai_provider.py 契约）。 */
@Service
public class ProviderService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiProviderMapper providerMapper;
    private final OpenAiCompatClient llm;
    private final CryptoUtils crypto;

    public ProviderService(AiProviderMapper providerMapper, OpenAiCompatClient llm,
                           @Value("${APE_JWT_SECRET:apeadmin-dev-secret-change-me-in-prod-2026}") String secret) {
        this.providerMapper = providerMapper;
        this.llm = llm;
        this.crypto = new CryptoUtils(secret);
    }

    public Map<String, Object> page(long page, long pageSize) {
        List<AiProviderDO> all = providerMapper.selectList(Wrappers.<AiProviderDO>lambdaQuery()
                .orderByAsc(AiProviderDO::getSort).orderByAsc(AiProviderDO::getId));
        int from = (int) Math.min((page - 1) * pageSize, all.size());
        int to = (int) Math.min(from + pageSize, all.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("page", page);
        out.put("page_size", pageSize);
        out.put("items", all.subList(from, to).stream().map(this::toOut).toList());
        return out;
    }

    /** 启用中的供应商（下拉用）。 */
    public List<Map<String, Object>> listEnabled() {
        return providerMapper.selectList(Wrappers.<AiProviderDO>lambdaQuery()
                        .eq(AiProviderDO::getEnabled, 1)
                        .orderByAsc(AiProviderDO::getSort).orderByAsc(AiProviderDO::getId))
                .stream().map(this::toOut).toList();
    }

    public Long create(String name, String providerType, String baseUrl, String apiKey,
                       List<String> models, Integer enabled, Integer sort, String remark, String headers) {
        Long exists = providerMapper.selectCount(Wrappers.<AiProviderDO>lambdaQuery()
                .eq(AiProviderDO::getName, name));
        if (exists > 0) {
            throw new BizException(ErrorCode.CONFLICT, "供应商名称已存在: " + name);
        }
        AiProviderDO p = new AiProviderDO();
        p.setName(name);
        p.setProviderType(providerType == null || providerType.isBlank() ? "openai_compatible" : providerType);
        p.setBaseUrl(baseUrl);
        p.setApiKeyEnc(crypto.encrypt(apiKey));
        p.setHeaders(headers == null || headers.isBlank() ? "{}" : headers);
        p.setModels(models == null ? "[]" : serialize(models));
        p.setEnabled(enabled == null ? 1 : enabled);
        p.setSort(sort == null ? 0 : sort);
        p.setRemark(remark);
        providerMapper.insert(p);
        return p.getId();
    }

    public void update(Long id, String name, String providerType, String baseUrl, String apiKey,
                       List<String> models, Integer enabled, Integer sort, String remark, String headers) {
        AiProviderDO p = providerMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        if (name != null) {
            p.setName(name);
        }
        if (providerType != null) {
            p.setProviderType(providerType);
        }
        if (baseUrl != null) {
            p.setBaseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            p.setApiKeyEnc(crypto.encrypt(apiKey));
        }
        if (models != null) {
            p.setModels(serialize(models));
        }
        if (enabled != null) {
            p.setEnabled(enabled);
        }
        if (sort != null) {
            p.setSort(sort);
        }
        if (remark != null) {
            p.setRemark(remark);
        }
        if (headers != null && !headers.isBlank()) {
            p.setHeaders(headers);
        }
        providerMapper.updateById(p);
    }

    public void delete(Long id) {
        if (providerMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        providerMapper.deleteById(id);
    }

    /** 连通性测试：GET /models。 */
    public Map<String, Object> test(Long id) {
        AiProviderDO p = providerMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        String apiKey = p.getApiKeyEnc() == null ? "" : crypto.decrypt(p.getApiKeyEnc());
        String baseUrl = p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                ? "https://api.deepseek.com" : p.getBaseUrl();
        try {
            List<String> models = llm.listModels(apiKey, baseUrl, p.getHeaders());
            return Map.of("ok", true, "models", models);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** 解析 provider 供 RunEngine 使用。 */
    public AiProviderDO resolve(Long providerId) {
        if (providerId != null) {
            AiProviderDO p = providerMapper.selectById(providerId);
            if (p == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "供应商不存在");
            }
            if (p.getEnabled() == null || p.getEnabled() != 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "供应商已停用");
            }
            return p;
        }
        List<AiProviderDO> enabled = providerMapper.selectList(Wrappers.<AiProviderDO>lambdaQuery()
                .eq(AiProviderDO::getEnabled, 1)
                .orderByAsc(AiProviderDO::getSort).orderByAsc(AiProviderDO::getId)
                .last("LIMIT 1"));
        if (enabled.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "尚未配置 AI 供应商");
        }
        return enabled.get(0);
    }

    private Map<String, Object> toOut(AiProviderDO p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("provider_type", p.getProviderType());
        m.put("base_url", p.getBaseUrl());
        m.put("models", parseModels(p.getModels()));
        m.put("enabled", p.getEnabled());
        m.put("sort", p.getSort());
        m.put("remark", p.getRemark());
        m.put("api_key_masked", mask(p));
        return m;
    }

    private String mask(AiProviderDO p) {
        try {
            return CryptoUtils.mask(crypto.decrypt(p.getApiKeyEnc()));
        } catch (Exception e) {
            return "****";
        }
    }

    private static List<String> parseModels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> list = JSON.readValue(json, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String serialize(List<String> models) {
        try {
            return JSON.writeValueAsString(models);
        } catch (Exception e) {
            return "[]";
        }
    }
}
