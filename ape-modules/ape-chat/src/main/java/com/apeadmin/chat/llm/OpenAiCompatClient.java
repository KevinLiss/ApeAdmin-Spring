package com.apeadmin.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * OpenAI 兼容协议客户端（JDK HttpClient）。
 *
 * <p>支持流式（/chat/completions stream=true）与非流式两种调用；
 * 流式实现：逐行读 SSE（data: 前缀）、content delta 直通回调、
 * tool_calls 按 index 归并（name/arguments 跨 chunk 拼接）、
 * 末 chunk usage 提取（choices 空数组 + usage 字段）。
 *
 * <p>网络层重试：连接失败/5xx 时最多重试 2 次（对齐 Python 版 LlmProvider 的重试语义）。
 */
@Slf4j
@Component
public class OpenAiCompatClient {

    private static final ObjectMapper JSON = new ObjectMapper();   // LLM 协议字段独立实例（snake 原样）

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${ape.llm.timeout-seconds:120}")
    private long timeoutSeconds;

    @Value("${ape.llm.max-retries:2}")
    private int maxRetries;

    /**
     * 流式对话。listener.onDone() 在流结束时必然被调用（错误时抛出异常）。
     *
     * @param apiKey   明文 API Key
     * @param baseUrl  如 https://api.deepseek.com
     * @param headers  provider 自定义额外请求头（JSON 字符串，可为 null）
     */
    public void streamChat(String model, List<Map<String, Object>> messages, String apiKey,
                           String baseUrl, String headers, Double temperature, Integer maxTokens,
                           List<Map<String, Object>> tools, StreamListener listener) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        executeWithRetry(() -> doStream(body, apiKey, baseUrl, headers, listener));
    }

    /** 非流式对话，返回完整响应 JSON。 */
    public JsonNode chat(String model, List<Map<String, Object>> messages, String apiKey,
                         String baseUrl, String headers, Double temperature, Integer maxTokens,
                         List<Map<String, Object>> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        return executeWithRetry(() -> doChat(body, apiKey, baseUrl, headers));
    }

    /** 连通性测试：GET /models，返回模型 id 列表。 */
    public List<String> listModels(String apiKey, String baseUrl, String headers) {
        return executeWithRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET();
            applyExtraHeaders(b, headers);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("模型接口返回 " + resp.statusCode() + ": "
                        + truncate(resp.body()));
            }
            JsonNode data = JSON.readTree(resp.body()).path("data");
            List<String> ids = new ArrayList<>();
            for (JsonNode m : data) {
                ids.add(m.path("id").asText(""));
            }
            return ids;
        });
    }

    // ---------------------------------------------------------------- 内部实现

    private Void doStream(Map<String, Object> body, String apiKey, String baseUrl,
                          String headers, StreamListener listener) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        applyExtraHeaders(b, headers);

        HttpResponse<java.io.InputStream> resp =
                http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("模型接口返回 " + resp.statusCode() + ": " + truncate(err));
        }

        // 跨 chunk 归并状态
        StringBuilder content = new StringBuilder();
        List<StreamListener.ToolCall> collected = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        boolean hasToolCalls = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String chunk = line.substring(5).trim();
                if (chunk.equals("[DONE]")) {
                    break;
                }
                if (chunk.isEmpty()) {
                    continue;
                }
                JsonNode root;
                try {
                    root = JSON.readTree(chunk);
                } catch (Exception ignore) {
                    continue;
                }
                // usage（末 chunk：choices 空数组 + usage 非空）
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode() && !usage.isNull() && usage.has("prompt_tokens")) {
                    listener.onUsage(usage.path("prompt_tokens").asLong(0),
                            usage.path("completion_tokens").asLong(0));
                }
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                String c = delta.path("content").asText(null);
                if (c != null && !c.isEmpty()) {
                    content.append(c);
                    listener.onContent(c);
                }
                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray() && !tcs.isEmpty()) {
                    hasToolCalls = true;
                    for (JsonNode tc : tcs) {
                        int idx = tc.path("index").asInt(0);
                        while (ids.size() <= idx) {
                            ids.add("");
                            names.add("");
                            arguments.add("");
                        }
                        String id = tc.path("id").asText(null);
                        if (id != null && !id.isEmpty()) {
                            ids.set(idx, id);
                        }
                        String name = tc.path("function").path("name").asText(null);
                        if (name != null && !name.isEmpty()) {
                            names.set(idx, names.get(idx) + name);
                        }
                        String args = tc.path("function").path("arguments").asText(null);
                        if (args != null && !args.isEmpty()) {
                            arguments.set(idx, arguments.get(idx) + args);
                        }
                    }
                }
            }
        }
        if (hasToolCalls) {
            for (int i = 0; i < ids.size(); i++) {
                collected.add(new StreamListener.ToolCall(
                        ids.get(i).isEmpty() ? "call_" + i : ids.get(i),
                        names.get(i),
                        arguments.get(i).isEmpty() ? "{}" : arguments.get(i)));
            }
            listener.onToolCalls(collected);
        }
        listener.onDone();
        return null;
    }

    private JsonNode doChat(Map<String, Object> body, String apiKey, String baseUrl, String headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        applyExtraHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("模型接口返回 " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        return JSON.readTree(resp.body());
    }

    @SuppressWarnings("unchecked")
    private void applyExtraHeaders(HttpRequest.Builder b, String headersJson) {
        if (headersJson == null || headersJson.isBlank() || headersJson.equals("{}")) {
            return;
        }
        try {
            Map<String, String> map = JSON.readValue(headersJson, Map.class);
            map.forEach(b::header);
        } catch (Exception e) {
            log.warn("provider 自定义 headers 解析失败（忽略）: {}", headersJson);
        }
    }

    /** 网络层重试：连接异常/IOException 重试，HTTP 业务错误不重试。 */
    private <T> T executeWithRetry(Callable<T> action) {
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (java.io.IOException e) {
                last = e;
                log.warn("LLM 调用网络失败（第 {} 次）：{}", attempt + 1, e.getMessage());
            } catch (RuntimeException e) {
                throw e;   // HTTP 4xx/5xx 业务错误：直接抛
            } catch (Exception e) {
                throw new IllegalStateException("LLM 调用失败", e);
            }
        }
        throw new IllegalStateException("模型接口网络异常（已重试 " + maxRetries + " 次）", last);
    }

    private static String trimSlash(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
