package com.apeadmin.chat.agent;

import com.apeadmin.chat.crypto.CryptoUtils;
import com.apeadmin.chat.domain.AiProviderDO;
import com.apeadmin.chat.llm.OpenAiCompatClient;
import com.apeadmin.chat.llm.StreamListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 对话代理：编排 LLM 调用 + 工具执行循环（最多 5 轮，对齐 Python 版 agent）。
 *
 * <p>事件通过回调吐出（7-type 扁平契约中的动态部分）：
 * content / tool_call / tool_result；done 与 usage 由调用方（RunEngine/直连流）收尾。
 */
@Slf4j
@Service
public class AiAgentService {

    /** 管理助手系统提示词（会话级 system_prompt 快照可覆盖，供插件人设）。 */
    public static final String SYSTEM_PROMPT = """
            你是 ApeAdmin 智能管理助手，可以帮助用户通过自然语言操作后台管理系统。

            你的能力：
            1. 用户管理：查询用户列表、查看用户详情、创建用户、更新用户信息、删除用户
            2. 系统统计：用户/角色/菜单/部门总数统计

            使用规则：
            - 当用户的请求涉及系统操作时，调用对应的工具完成任务
            - 工具调用后，用简洁的中文总结执行结果
            - 如果权限不足，告知用户并说明需要什么权限
            - 对于普通对话，直接友好地回答
            - 创建/更新操作时，如果用户未提供必填参数，先询问用户
            """;

    private static final int MAX_TOOL_ROUNDS = 5;

    private final OpenAiCompatClient llm;
    private final SystemTools tools;
    private final CryptoUtils crypto;

    public AiAgentService(OpenAiCompatClient llm, SystemTools tools,
                           @Value("${APE_JWT_SECRET:apeadmin-dev-secret-change-me-in-prod-2026}") String secret) {
        this.llm = llm;
        this.tools = tools;
        this.crypto = new CryptoUtils(secret);
    }

    /** 直连流式对话事件（ChatStreamController 用，与 Python chat_stream 等价）。 */
    public interface AgentEvent {
    }

    public record ContentEvent(String content) implements AgentEvent {
    }

    public record ToolCallEvent(String name, Map<String, Object> arguments) implements AgentEvent {
    }

    public record ToolResultEvent(String name, Object result) implements AgentEvent {
    }

    public record UsageEvent(long promptTokens, long completionTokens) implements AgentEvent {
    }

    /**
     * 流式对话（含工具循环）。事件通过 emitter 吐出；usage 通过 usageConsumer 汇报。
     */
    public void streamChat(List<Map<String, Object>> history, AiProviderDO provider, String model,
                           boolean enableTools, Long userId, String systemPrompt,
                           Consumer<AgentEvent> emitter, UsageSink usageSink) {
        String apiKey = provider.getApiKeyEnc() == null ? "" : crypto.decrypt(provider.getApiKeyEnc());
        String baseUrl = provider.getBaseUrl();
        String modelName = model != null && !model.isBlank() ? model : defaultModel(provider);
        List<Map<String, Object>> toolDefs = enableTools ? tools.toolDefinitions() : null;

        List<Map<String, Object>> llmMessages = new ArrayList<>();
        llmMessages.add(Map.of("role", "system", "content",
                systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : SYSTEM_PROMPT));
        llmMessages.addAll(history);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            final StringBuilder collectedContent = new StringBuilder();
            final List<StreamListener.ToolCall>[] collectedCalls = new List[]{null};

            llm.streamChat(modelName, llmMessages, apiKey, baseUrl, provider.getHeaders(),
                    0.7, 2000, toolDefs, new StreamListener() {
                        @Override
                        public void onContent(String delta) {
                            collectedContent.append(delta);
                            emitter.accept(new ContentEvent(delta));
                        }

                        @Override
                        public void onToolCalls(List<ToolCall> calls) {
                            collectedCalls[0] = calls;
                        }

                        @Override
                        public void onUsage(long promptTokens, long completionTokens) {
                            if (usageSink != null) {
                                usageSink.onUsage(promptTokens, completionTokens);
                            }
                        }

                        @Override
                        public void onDone() {
                        }
                    });

            List<StreamListener.ToolCall> calls = collectedCalls[0];
            if (calls == null || calls.isEmpty()) {
                return;   // 本轮无工具调用：对话完成（content 已流式吐出）
            }

            // assistant 消息（含 tool_calls）回填上下文
            List<StreamListener.ToolCall> normalized = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                StreamListener.ToolCall tc = calls.get(i);
                normalized.add(new StreamListener.ToolCall(
                        tc.id().isEmpty() ? "call_" + round + "_" + i : tc.id(),
                        tc.name(),
                        tc.arguments().isBlank() ? "{}" : tc.arguments()));
            }
            llmMessages.add(StreamListener.msgToMap(new StreamListener.Msg(
                    "assistant", collectedContent.length() == 0 ? null : collectedContent.toString(),
                    normalized, null)));

            // 逐个执行工具 → tool 消息回填
            for (StreamListener.ToolCall tc : normalized) {
                Map<String, Object> args = new LinkedHashMap<>();
                try {
                    args = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(tc.arguments(), Map.class);
                } catch (Exception ignore) {
                    // 参数解析失败按空参
                }
                emitter.accept(new ToolCallEvent(tc.name(), args));

                if (!tools.checkPermission(tc.name(), userId)) {
                    String denied = "{\"error\":\"权限不足，需要 " + String.join(",", permOf(tc.name())) + "\"}";
                    emitter.accept(new ToolResultEvent(tc.name(), Map.of("error", "权限不足")));
                    llmMessages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "content", denied));
                    continue;
                }
                String resultJson = tools.execute(tc.name(), tc.arguments(), userId);
                Object resultObj;
                try {
                    resultObj = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resultJson, Object.class);
                } catch (Exception ignore) {
                    resultObj = resultJson;
                }
                emitter.accept(new ToolResultEvent(tc.name(), resultObj));
                llmMessages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "content", resultJson));
            }
            // 继续下一轮（LLM 总结工具结果）
        }
        log.warn("AI 对话工具轮次达到上限（{}），强制结束", MAX_TOOL_ROUNDS);
    }

    /** 默认模型（provider.models 首个；空则 deepseek-chat）。 */
    private String defaultModel(AiProviderDO provider) {
        try {
            List<String> models = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(provider.getModels() == null || provider.getModels().isBlank()
                            ? "[]" : provider.getModels(), List.class);
            if (!models.isEmpty()) {
                return models.get(0);
            }
        } catch (Exception ignore) {
            // 解析失败走默认
        }
        return "deepseek-chat";
    }

    private List<String> permOf(String toolName) {
        return switch (toolName) {
            case "get_user_list", "get_user_detail" -> List.of("system:user:list");
            case "create_user" -> List.of("system:user:add");
            case "update_user" -> List.of("system:user:edit");
            case "delete_user" -> List.of("system:user:delete");
            default -> List.of();
        };
    }

    /** usage 汇报接口。 */
    public interface UsageSink {
        void onUsage(long promptTokens, long completionTokens);
    }
}
