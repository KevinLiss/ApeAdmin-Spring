package com.apeadmin.mcp;

import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON Schema 校验器（零依赖，覆盖 MCP 工具常用子集）：
 * 校验 arguments 是否满足工具 inputSchema 的 required 必填与 properties 基本类型
 * （string / number / integer / boolean / array / object）。
 *
 * <p>动机：此前 14 个内置工具手写 schema 只是"摆设"——坏参数完全靠 handler 自己解析失败。
 * 引入入口校验后，坏参数在执行前被拦截并返回规范错误（对照 mcp-java-sdk 内置
 * JsonSchemaValidator 的服务端校验姿势；完整实现见官方 SDK，此处按项目 schema 面裁剪）。
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /**
     * 校验 arguments 是否符合 schema。
     *
     * @return null 合法；否则返回第一条错误描述
     */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null) {
            return null;
        }
        Object type = schema.get("type");
        if (type == null || !"object".equals(type)) {
            return null;  // 仅处理 object 顶层 schema
        }

        Object required = schema.get("required");
        if (required instanceof List<?> reqList) {
            for (Object r : reqList) {
                if (!args.containsKey(r)) {
                    return "缺少必填参数: " + r;
                }
            }
        }

        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> propMap)) {
            return null;
        }
        for (Map.Entry<?, ?> e : propMap.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!args.containsKey(key)) {
                continue;
            }
            Object value = args.get(key);
            if (!(e.getValue() instanceof Map<?, ?> propSchema)) {
                continue;
            }
            String err = validateType(key, value, propSchema);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static String validateType(String key, Object value, Map<?, ?> propSchema) {
        String expected = String.valueOf(propSchema.get("type"));
        if ("string".equals(expected)) {
            if (!(value instanceof String)) {
                return "参数 " + key + " 应为字符串";
            }
        } else if ("number".equals(expected)) {
            if (!(value instanceof Number)) {
                return "参数 " + key + " 应为数字";
            }
        } else if ("integer".equals(expected)) {
            if (!(value instanceof Integer || value instanceof Long)) {
                return "参数 " + key + " 应为整数";
            }
        } else if ("boolean".equals(expected)) {
            if (!(value instanceof Boolean)) {
                return "参数 " + key + " 应为布尔值";
            }
        } else if ("array".equals(expected)) {
            if (!(value instanceof List)) {
                return "参数 " + key + " 应为数组";
            }
        } else if ("object".equals(expected)) {
            if (!(value instanceof Map)) {
                return "参数 " + key + " 应为对象";
            }
        }
        // 未知 type：不校验，宽松通过
        return null;
    }
}
