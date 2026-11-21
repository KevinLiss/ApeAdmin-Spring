package com.apeadmin.server.config;

import cn.dev33.satoken.json.SaJsonTemplate;

import java.util.Map;

/**
 * sa-token JSON 模板：hutool 实现。
 *
 * <p>背景：SB4 下 sa-token 的 jackson 插件不兼容已排除，core 内置
 * SaJsonTemplateDefaultImpl 四个方法全部抛 NotImplException —— 持久化 Dao 的
 * Object/Session 层（JSON 字符串 ↔ SaSession）读写都需要真实 JSON 能力；
 * hutool-json 已随 sa-token-jwt 进入 classpath，直接复用。
 *
 * <p>注意一：JSON 整数默认解析为 Integer，Account-Session 的 loginId 写入侧是 Long，
 * 回读后做一次归一，避免 equals 比较失效。
 *
 * <p>注意二：空串/入参必须返回 null——hutool 的 toBean(null) 会返回空对象而非 null，
 * 会让 sa-token 把「key 不存在」误判为「已存在空会话」，导致会话永不落库。
 */
public class SaJsonTemplateHutool implements SaJsonTemplate {

    @Override
    public String objectToJson(Object obj) {
        return cn.hutool.json.JSONUtil.toJsonStr(obj);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T jsonToObject(String jsonStr, Class<T> type) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }
        T bean = cn.hutool.json.JSONUtil.toBean(jsonStr, type);
        if (bean instanceof cn.dev33.satoken.session.SaSession session
                && session.getLoginId() instanceof Integer i) {
            session.setLoginId(i.longValue());
        }
        return bean;
    }

    @Override
    public Object jsonToObject(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }
        return cn.hutool.json.JSONUtil.parse(jsonStr);
    }

    @Override
    public Map<String, Object> jsonToMap(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }
        return cn.hutool.json.JSONUtil.toBean(jsonStr, Map.class);
    }
}
