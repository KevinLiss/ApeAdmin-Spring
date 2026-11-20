package com.apeadmin.chat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.apeadmin.common.api.R;
import com.apeadmin.chat.agent.AiAgentService;
import com.apeadmin.chat.domain.AiProviderDO;
import com.apeadmin.chat.service.ProviderService;
import com.apeadmin.system.security.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直连流式对话（对齐前端 /ai/chat/stream 与 /ai/chat 契约，非 Run 架构的轻量路径）。
 *
 * <p>事件：content / tool_call / tool_result / done（7-type 中的动态部分，无 run 生命周期）。
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class ChatStreamController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiAgentService agent;
    private final ProviderService providerService;

    @Data
    public static class ChatMessageReq {
        private String role;
        private String content;
    }

    @Data
    public static class ChatReq {
        private List<ChatMessageReq> messages;
        private Long provider_id;
        private String model;
        private Integer max_tokens;
        private Double temperature;
        private Boolean enable_tools;
        private String system_prompt;
    }

    @SaCheckLogin
    @PostMapping("/stream")
    public StreamingResponseBody stream(@RequestBody ChatReq body) {
        var ctx = UserContextHolder.require();
        AiProviderDO provider = providerService.resolve(body.getProvider_id());
        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatMessageReq m : body.getMessages() == null ? List.<ChatMessageReq>of() : body.getMessages()) {
            history.add(Map.of("role", m.getRole() == null ? "user" : m.getRole(),
                    "content", m.getContent() == null ? "" : m.getContent()));
        }
        boolean enableTools = body.getEnable_tools() == null || body.getEnable_tools();
        return (OutputStream out) -> {
            try (var w = new java.io.PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
                agent.streamChat(history, provider, body.getModel(), enableTools, ctx.id(),
                        body.getSystem_prompt(),
                        event -> {
                            try {
                                String payload;
                                if (event instanceof AiAgentService.ContentEvent ce) {
                                    payload = JSON.writeValueAsString(Map.of("type", "content", "content", ce.content()));
                                } else if (event instanceof AiAgentService.ToolCallEvent te) {
                                    payload = JSON.writeValueAsString(Map.of("type", "tool_call",
                                            "name", te.name(), "arguments", te.arguments()));
                                } else if (event instanceof AiAgentService.ToolResultEvent tre) {
                                    payload = JSON.writeValueAsString(Map.of("type", "tool_result",
                                            "name", tre.name(), "result", tre.result()));
                                } else {
                                    return;
                                }
                                w.write("data: " + payload + "\n\n");
                                w.flush();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        null);
                w.write("data: " + JSON.writeValueAsString(Map.of("type", "done")) + "\n\n");
                w.flush();
            } catch (Exception e) {
                try {
                    String err = JSON.writeValueAsString(Map.of("type", "error", "message",
                            e.getMessage() == null ? "生成失败" : e.getMessage()));
                    out.write(("data: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignore) {
                    // 连接已断
                }
            }
        };
    }

    /** 非流式对话（轻量调用方）。 */
    @SaCheckLogin
    @PostMapping
    public R<Object> chat(@RequestBody ChatReq body) {
        var ctx = UserContextHolder.require();
        AiProviderDO provider = providerService.resolve(body.getProvider_id());
        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatMessageReq m : body.getMessages() == null ? List.<ChatMessageReq>of() : body.getMessages()) {
            history.add(Map.of("role", m.getRole() == null ? "user" : m.getRole(),
                    "content", m.getContent() == null ? "" : m.getContent()));
        }
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        boolean enableTools = body.getEnable_tools() == null || body.getEnable_tools();
        agent.streamChat(history, provider, body.getModel(), enableTools, ctx.id(),
                body.getSystem_prompt(),
                event -> {
                    if (event instanceof AiAgentService.ContentEvent ce) {
                        answer.append(ce.content());
                    } else if (event instanceof AiAgentService.ToolCallEvent te) {
                        toolEvents.add(Map.of("type", "tool_call", "name", te.name(), "arguments", te.arguments()));
                    } else if (event instanceof AiAgentService.ToolResultEvent tre) {
                        toolEvents.add(Map.of("type", "tool_result", "name", tre.name(), "result", tre.result()));
                    }
                },
                null);
        return R.ok(Map.of("content", answer.toString(), "tool_calls", toolEvents));
    }
}
