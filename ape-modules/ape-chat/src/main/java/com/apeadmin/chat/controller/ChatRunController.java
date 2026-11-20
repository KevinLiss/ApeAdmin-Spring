package com.apeadmin.chat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import com.apeadmin.chat.domain.ChatRunDO;
import com.apeadmin.chat.domain.ChatSessionDO;
import com.apeadmin.chat.mapper.ChatRunMapper;
import com.apeadmin.chat.mapper.ChatSessionMapper;
import com.apeadmin.chat.run.RunEngine;
import com.apeadmin.chat.service.ProviderService;
import com.apeadmin.chat.service.SessionService;
import com.apeadmin.system.security.UserContextHolder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * AI Run 端点（对齐前端 /ai/chat/runs 契约，Run 架构）：
 *
 * <pre>
 * POST /ai/chat/runs                  → 提交（立即返回 run_id）
 * GET  /ai/chat/runs/{id}/events?seen_events=N → SSE 订阅（snapshot + 增量 + 终态）
 * GET  /ai/chat/runs/{id}             → 轮询状态（SSE 不可用回退）
 * POST /ai/chat/runs/{id}/stop       → 停止
 * GET  /ai/chat/runs/session/{sid}/active → 会话活跃 run
 * </pre>
 *
 * <p>SSE 输出格式为 7-type 扁平事件流（前端 fetch+getReader 逐行读 data: JSON）：
 * snapshot / run_start / content / tool_call / tool_result / run_end / error。
 */
@RestController
@RequestMapping("/api/v1/ai/chat/runs")
@RequiredArgsConstructor
public class ChatRunController {

    private final RunEngine engine;
    private final ChatRunMapper runMapper;
    private final ChatSessionMapper sessionMapper;
    private final SessionService sessionService;
    private final ProviderService providerService;

    @Data
    public static class RunCreateReq {
        private Long session_id;      // null → 新建会话
        private String question;
        private Long provider_id;
        private String model;
        private Boolean enable_tools;
    }

    /** 每用户每日调用上限（内存计数；0=不限制）。 */
    @org.springframework.beans.factory.annotation.Value("${ape.chat.daily-limit:100}")
    private long dailyLimit;

    private final java.util.Map<Long, java.util.Map<String, Long>> dailyUsage = new java.util.concurrent.ConcurrentHashMap<>();

    @SaCheckLogin
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody RunCreateReq body) {
        var ctx = UserContextHolder.require();
        checkQuota(ctx.id());
        if (body.getQuestion() == null || body.getQuestion().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        String question = body.getQuestion().strip();

        // 会话：显式 id（属主校验）> 新建
        ChatSessionDO session;
        if (body.getSession_id() != null) {
            session = sessionService.owned(body.getSession_id(), ctx.id());
        } else {
            session = new ChatSessionDO();
            session.setUserId(ctx.id());
            session.setTitle("新对话");
            session.setMessageCount(0);
            sessionMapper.insert(session);
        }
        Long providerId = body.getProvider_id();
        if (providerId == null) {
            providerId = session.getProviderId();
        }
        String model = body.getModel() != null && !body.getModel().isBlank()
                ? body.getModel() : session.getModel();
        boolean enableTools = body.getEnable_tools() == null || body.getEnable_tools();

        ChatRunDO run = engine.enqueue(ctx.id(), session.getId(), providerId, model,
                question, enableTools);
        return R.ok(Map.of(
                "run_id", run.getId(),
                "session_id", session.getId(),
                "status", run.getStatus(),
                "provider_id", providerId == null ? 0 : providerId,
                "model", model == null ? "" : model,
                "title", session.getTitle() == null ? "新对话" : session.getTitle()), "已提交");
    }

    @SaCheckLogin
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody events(@PathVariable Long id,
                                        @RequestParam(name = "seen_events", defaultValue = "0") int seenEvents) {
        ChatRunDO run = owned(id);
        return (OutputStream out) -> {
            try (var w = new java.io.PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
                RunEngine.EventSink sink = payload -> {
                    w.write("data: " + payload + "\n\n");
                    w.flush();
                    return !w.checkError();
                };
                engine.streamRunEvents(run, seenEvents, sink);
            } catch (Exception e) {
                // 流中途异常：尽力吐一个 error 事件再关流
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String err = mapper.writeValueAsString(Map.of("type", "error", "message",
                            e.getMessage() == null ? "流中断" : e.getMessage()));
                    out.write(("data: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignore) {
                    // 连接已断
                }
            }
        };
    }

    @SaCheckLogin
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return R.ok(engine.runState(owned(id)));
    }

    @SaCheckLogin
    @PostMapping("/{id}/stop")
    public R<Map<String, Object>> stop(@PathVariable Long id) {
        ChatRunDO run = owned(id);
        if (RunEngine.TERMINAL.contains(run.getStatus())) {
            return R.ok(Map.of("run_id", run.getId(), "status", run.getStatus()), "已结束，无需停止");
        }
        ChatRunDO updated = engine.stopRun(run);
        return R.ok(Map.of("run_id", updated.getId(), "status", updated.getStatus()), "正在停止");
    }

    @SaCheckLogin
    @GetMapping("/session/{sessionId}/active")
    public R<Object> activeRun(@PathVariable Long sessionId) {
        var ctx = UserContextHolder.require();
        sessionService.owned(sessionId, ctx.id());
        Map<String, Object> data = engine.activeRun(sessionId, ctx.id());
        return R.ok(data == null ? Map.of() : data);
    }

    // ---------------------------------------------------------------- 辅助

    private ChatRunDO owned(Long runId) {
        var ctx = UserContextHolder.require();
        ChatRunDO run = runId == null ? null : runMapper.selectById(runId);
        if (run == null || !run.getUserId().equals(ctx.id())) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在");
        }
        return run;
    }

    private void checkQuota(Long userId) {
        if (dailyLimit <= 0) {
            return;
        }
        String today = java.time.LocalDate.now().toString().replace("-", "");
        java.util.Map<String, Long> entry = dailyUsage.computeIfAbsent(userId, k -> {
            java.util.Map<String, Long> m = new java.util.concurrent.ConcurrentHashMap<>();
            m.put("date", 0L);
            m.put("count", 0L);
            return m;
        });
        if (entry.get("date") != Long.parseLong(today)) {
            entry.put("date", Long.parseLong(today));
            entry.put("count", 0L);
        }
        if (entry.get("count") >= dailyLimit) {
            throw new BizException(ErrorCode.TOO_MANY_ATTEMPTS, "今日调用已达上限（" + dailyLimit + " 次）");
        }
        entry.merge("count", 1L, Long::sum);
    }
}
