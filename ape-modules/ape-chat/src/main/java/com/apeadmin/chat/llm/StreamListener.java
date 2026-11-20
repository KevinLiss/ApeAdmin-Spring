package com.apeadmin.chat.llm;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议流式客户端回调。
 * tool_calls 在流式响应中按 index 分片增量传输，本客户端内部完成归并
 * （name/arguments 跨 chunk 拼接），回调方拿到的是聚合后的完整定义。
 */
public interface StreamListener {

    /** 文本增量。 */
    void onContent(String delta);

    /** 本轮流式聚合完成后的全部工具调用（完整定义）。可能为空。 */
    void onToolCalls(List<ToolCall> calls);

    /** usage（部分供应商仅在末 chunk 带，choices 为空数组）。 */
    void onUsage(long promptTokens, long completionTokens);

    /** 流正常结束（[DONE] 或连接关闭）。 */
    void onDone();

    record ToolCall(String id, String name, String arguments) {
    }

    /** LLM 消息（OpenAI 兼容格式）。 */
    record Msg(String role, Object content, List<ToolCall> toolCalls, String toolCallId) {
        public static Msg text(String role, String content) {
            return new Msg(role, content, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> msgToMap(Msg m) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("role", m.role());
        if (m.toolCallId() != null) {
            map.put("tool_call_id", m.toolCallId());
        }
        if (m.content() != null) {
            map.put("content", m.content());
        } else if (m.role().equals("assistant") && m.toolCalls() != null) {
            map.put("content", null);
        }
        if (m.toolCalls() != null) {
            List<Object> tcs = new java.util.ArrayList<>();
            for (ToolCall tc : m.toolCalls()) {
                Map<String, Object> fn = new java.util.LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments() == null || tc.arguments().isBlank() ? "{}" : tc.arguments());
                Map<String, Object> t = new java.util.LinkedHashMap<>();
                t.put("id", tc.id());
                t.put("type", "function");   // "type":"function" 必带（DeepSeek 无则 400）
                t.put("function", fn);
                tcs.add(t);
            }
            map.put("tool_calls", tcs);
        }
        return map;
    }
}
