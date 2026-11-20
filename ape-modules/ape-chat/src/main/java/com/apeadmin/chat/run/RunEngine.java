package com.apeadmin.chat.run;

import com.apeadmin.chat.agent.AiAgentService;
import com.apeadmin.chat.domain.AiProviderDO;
import com.apeadmin.chat.domain.ChatMessageDO;
import com.apeadmin.chat.domain.ChatRunDO;
import com.apeadmin.chat.domain.ChatSessionDO;
import com.apeadmin.chat.mapper.AiProviderMapper;
import com.apeadmin.chat.mapper.ChatMessageMapper;
import com.apeadmin.chat.mapper.ChatRunMapper;
import com.apeadmin.chat.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run 引擎（对齐 Python 版 runner.py）：
 *
 * <p>enqueue_run：落库 pending run + 用户消息，立即返回（与 HTTP 生命周期解耦——
 * 关页面不杀生成）。每 session 一个 worker 虚拟线程：FIFO 认领该 session 的 pending run
 * （同 session 排队，不同 session 并行）。
 *
 * <p>订阅（streamRunEvents）：先发全量 snapshot（answer/tool_events 现值），
 * 再重放 seen_events 之后的事件增量，跟随实时更新直到终态；断线重连带 seen_events。
 * 无活跃 buffer（已结束/重启后）→ 只发 snapshot 即关闭。
 *
 * <p>stop_run：置取消标记，worker 在下一事件边界感知；已生成内容保留。
 * pending 状态撤销时删除孤儿用户消息（transcript 不留未回应气泡）。
 *
 * <p>recover：进程重启后 pending/running 全部标 failed（worker 已消失）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunEngine implements ApplicationRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final List<String> TERMINAL = List.of("done", "failed", "stopped");
    private static final long DB_FLUSH_INTERVAL_MS = 500;
    private static final int CONTEXT_WINDOW = 40;

    private final ChatRunMapper runMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AiProviderMapper providerMapper;
    private final AiAgentService agent;

    /** run_id → 运行时状态。 */
    final Map<Long, RunBuffer> buffers = new ConcurrentHashMap<>();
    /** session_id → worker 线程。 */
    final Map<Long, Thread> workers = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 数据结构

    static final class RunBuffer {
        final long runId;
        final long sessionId;
        final String question;
        final boolean enableTools;
        String status = "pending";
        String answer = "";
        final List<Map<String, Object>> toolEvents = new ArrayList<>();
        String error;
        final List<String> events = new ArrayList<>();   // 缓冲 SSE payload（index = 绝对偏移）
        boolean cancelRequested;
        long lastFlush;
        long promptTokens;
        long completionTokens;

        RunBuffer(long runId, long sessionId, String question, boolean enableTools) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.question = question;
            this.enableTools = enableTools;
        }

        synchronized void publish(Map<String, Object> payload) {
            try {
                events.add(JSON.writeValueAsString(payload));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            notifyAll();
        }

        synchronized void await(long timeoutMs) throws InterruptedException {
            wait(timeoutMs);
        }
    }

    // ---------------------------------------------------------------- 对外 API

    /** 提交运行：落库 + 建 buffer + 确保 worker 存在。返回 run。 */
    public ChatRunDO enqueue(Long userId, Long sessionId, Long providerId, String model,
                              String question, boolean enableTools) {
        ChatRunDO run = new ChatRunDO();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("pending");
        run.setProviderId(providerId);
        run.setModel(model);
        run.setEnableTools(enableTools ? 1 : 0);
        run.setQuestion(question);
        run.setAnswer("");
        runMapper.insert(run);

        RunBuffer buf = new RunBuffer(run.getId(), sessionId, question, enableTools);
        buffers.put(run.getId(), buf);

        appendMessage(sessionId, run.getId(), "user", question, null);

        workers.compute(sessionId, (sid, existing) -> {
            if (existing == null || !existing.isAlive()) {
                Thread t = Thread.ofVirtual().name("chat-worker-s" + sid).start(() -> sessionWorker(sid));
                return t;
            }
            return existing;
        });
        return run;
    }

    /** 订阅运行事件（SSE 转发器逐条调用）。返回 false = 流结束。 */
    public interface EventSink {
        boolean accept(String payload) throws Exception;
    }

    /** 逐条输出事件直到终态。终态返回 true，非正常返回 false。20s 无事件输出 SSE 心跳注释。 */
    public boolean streamRunEvents(ChatRunDO run, int seenEvents, EventSink sink) throws Exception {
        RunBuffer buf = buffers.get(run.getId());
        if (buf == null) {
            sink.accept(snapshotPayload(run));
            return true;
        }
        synchronized (buf) {
            sink.accept(liveSnapshotPayload(buf, run.getId()));
        }
        int offset = Math.max(0, seenEvents);
        while (true) {
            String event = null;
            boolean timeout = false;
            synchronized (buf) {
                while (offset >= buf.events.size()) {
                    if (TERMINAL.contains(buf.status)) {
                        return true;
                    }
                    try {
                        buf.wait(20_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    // 唤醒后仍无新事件且非终态 → 心跳窗口耗尽
                    if (offset >= buf.events.size() && !TERMINAL.contains(buf.status)) {
                        timeout = true;
                        break;
                    }
                }
                if (!timeout) {
                    event = buf.events.get(offset);
                }
            }
            if (timeout) {
                if (!sink.accept(": keep-alive")) {
                    return false;
                }
                continue;   // 未消费事件，offset 不变
            }
            offset++;
            // 发送移出锁外：避免慢消费者持锁阻塞 worker 的 publish
            if (!sink.accept(event)) {
                return false;
            }
        }
    }

    /** 请求停止。已生成内容保留；pending 撤销时删孤儿用户消息。 */
    public ChatRunDO stopRun(ChatRunDO run) {
        RunBuffer buf = buffers.get(run.getId());
        if ("pending".equals(run.getStatus())) {
            List<ChatMessageDO> orphans = messageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                    .eq(ChatMessageDO::getSessionId, run.getSessionId())
                    .eq(ChatMessageDO::getRole, "user")
                    .eq(ChatMessageDO::getContent, run.getQuestion())
                    .orderByDesc(ChatMessageDO::getId));
            if (!orphans.isEmpty()) {
                messageMapper.deleteById(orphans.get(0).getId());
                ChatSessionDO s = sessionMapper.selectById(run.getSessionId());
                if (s != null && s.getMessageCount() != null && s.getMessageCount() > 0) {
                    s.setMessageCount(s.getMessageCount() - 1);
                    sessionMapper.updateById(s);
                }
            }
            run.setStatus("stopped");
            run.setError("任务已取消");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            if (buf != null) {
                synchronized (buf) {
                    buf.status = "stopped";
                    buf.cancelRequested = true;
                    buf.publish(Map.of("type", "run_end", "status", "stopped", "error", "任务已取消"));
                }
            }
            return run;
        }
        if ("running".equals(run.getStatus()) && buf != null) {
            synchronized (buf) {
                buf.cancelRequested = true;
            }
        }
        return run;
    }

    /** 会话的活跃 run（pending/running）+ 部分答案。 */
    public Map<String, Object> activeRun(Long sessionId, Long userId) {
        List<ChatRunDO> runs = runMapper.selectList(Wrappers.<ChatRunDO>lambdaQuery()
                .eq(ChatRunDO::getSessionId, sessionId)
                .eq(ChatRunDO::getUserId, userId)
                .in(ChatRunDO::getStatus, "pending", "running")
                .orderByDesc(ChatRunDO::getId));
        if (runs.isEmpty()) {
            return null;
        }
        ChatRunDO active = runs.get(0);
        RunBuffer buf = buffers.get(active.getId());
        int queued = 0;
        for (ChatRunDO r : runs) {
            if (!r.getId().equals(active.getId()) && "pending".equals(r.getStatus())) {
                queued++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", active.getId());
        out.put("session_id", sessionId);
        out.put("status", buf != null ? buf.status : active.getStatus());
        out.put("answer", buf != null ? buf.answer : active.getAnswer());
        out.put("tool_events", buf != null ? List.copyOf(buf.toolEvents) : parseToolEvents(active.getToolEvents()));
        out.put("error", active.getError());
        out.put("question", active.getQuestion());
        out.put("queued", queued);
        return out;
    }

    /** run 当前状态（轮询回退）。 */
    public Map<String, Object> runState(ChatRunDO run) {
        RunBuffer buf = buffers.get(run.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", run.getId());
        out.put("session_id", run.getSessionId());
        out.put("status", buf != null ? buf.status : run.getStatus());
        out.put("answer", buf != null ? buf.answer : run.getAnswer());
        out.put("tool_events", buf != null ? List.copyOf(buf.toolEvents) : parseToolEvents(run.getToolEvents()));
        out.put("error", buf != null ? buf.error : run.getError());
        return out;
    }

    /** 进程重启：孤儿 run 全部标 failed。 */
    @Override
    public void run(ApplicationArguments args) {
        List<ChatRunDO> orphans = runMapper.selectList(Wrappers.<ChatRunDO>lambdaQuery()
                .in(ChatRunDO::getStatus, "pending", "running"));
        for (ChatRunDO r : orphans) {
            boolean wasRunning = "running".equals(r.getStatus());
            r.setStatus("failed");
            r.setError(wasRunning ? "服务重启，本轮生成中断，请重新发送" : "服务重启，请重新发送");
            r.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(r);
        }
        if (!orphans.isEmpty()) {
            log.info("重启后回收 {} 个孤儿 chat run", orphans.size());
        }
    }

    // ---------------------------------------------------------------- worker

    private void sessionWorker(long sessionId) {
        try {
            while (true) {
                List<ChatRunDO> pending = runMapper.selectList(Wrappers.<ChatRunDO>lambdaQuery()
                        .eq(ChatRunDO::getSessionId, sessionId)
                        .eq(ChatRunDO::getStatus, "pending")
                        .orderByAsc(ChatRunDO::getId));
                if (pending.isEmpty()) {
                    break;
                }
                ChatRunDO next = pending.get(0);
                RunBuffer buf = buffers.get(next.getId());
                if (buf == null) {
                    ChatRunDO r = runMapper.selectById(next.getId());
                    if (r != null && "pending".equals(r.getStatus())) {
                        r.setStatus("stopped");
                        r.setError("任务已取消");
                        r.setFinishedAt(LocalDateTime.now());
                        runMapper.updateById(r);
                    }
                    continue;
                }
                executeRun(next.getId());
            }
        } catch (Throwable t) {
            log.error("session worker {} 崩溃", sessionId, t);
        } finally {
            workers.remove(sessionId);
            // 退出前复查：覆盖「查询为空 → enqueue 恰在此时落地」的竞态窗口
            Long remaining = runMapper.selectCount(Wrappers.<ChatRunDO>lambdaQuery()
                    .eq(ChatRunDO::getSessionId, sessionId)
                    .eq(ChatRunDO::getStatus, "pending"));
            if (remaining != null && remaining > 0) {
                workers.compute(sessionId, (sid, existing) -> {
                    if (existing == null || !existing.isAlive()) {
                        Thread t = Thread.ofVirtual().name("chat-worker-s" + sid).start(() -> sessionWorker(sid));
                        return t;
                    }
                    return existing;
                });
            }
        }
    }

    private void executeRun(long runId) {
        RunBuffer buf = buffers.get(runId);
        if (buf == null) {
            return;
        }
        ChatRunDO run = runMapper.selectById(runId);
        if (run == null || !List.of("pending", "running").contains(run.getStatus())) {
            buffers.remove(runId);
            return;
        }
        AiProviderDO provider = run.getProviderId() == null ? null : providerMapper.selectById(run.getProviderId());
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1) {
            finishRun(run, buf, "failed", "模型供应商不可用或已停用", true);
            return;
        }
        long startedAt = System.currentTimeMillis();
        run.setStatus("running");
        run.setStartedAt(LocalDateTime.now());
        runMapper.updateById(run);
        synchronized (buf) {
            buf.status = "running";
            buf.publish(Map.of("type", "run_start", "status", "running"));
        }

        List<Map<String, Object>> context = buildContext(run.getSessionId(), run.getQuestion());
        try {
            agent.streamChat(context, provider, run.getModel(), buf.enableTools, run.getUserId(),
                    sessionSystemPrompt(run.getSessionId()),
                    event -> {
                        if (buf.cancelRequested) {
                            throw new RunCancelled();
                        }
                        if (event instanceof AiAgentService.ContentEvent ce) {
                            synchronized (buf) {
                                buf.answer += ce.content();
                                buf.publish(Map.of("type", "content", "content", ce.content()));
                            }
                            flushAnswer(runId, buf);
                        } else if (event instanceof AiAgentService.ToolCallEvent te) {
                            synchronized (buf) {
                                buf.toolEvents.add(Map.of("type", "tool_call", "name", te.name(),
                                        "arguments", te.arguments()));
                                buf.publish(Map.of("type", "tool_call", "name", te.name(),
                                        "arguments", te.arguments()));
                            }
                        } else if (event instanceof AiAgentService.ToolResultEvent tre) {
                            synchronized (buf) {
                                buf.toolEvents.add(Map.of("type", "tool_result", "name", tre.name(),
                                        "result", tre.result()));
                                buf.publish(Map.of("type", "tool_result", "name", tre.name(),
                                        "result", tre.result()));
                            }
                        }
                    },
                    (pt, ct) -> {
                        buf.promptTokens = pt;
                        buf.completionTokens = ct;
                    });

            if (buf.cancelRequested) {
                throw new RunCancelled();
            }
            run.setDurationMs((int) (System.currentTimeMillis() - startedAt));
            run.setPromptTokens((int) buf.promptTokens);
            run.setCompletionTokens((int) buf.completionTokens);
            finishRun(run, buf, "done", null, true);
        } catch (RunCancelled e) {
            finishRun(run, buf, "stopped", null, true);
        } catch (Throwable t) {
            log.error("ChatRun {} 失败", runId, t);
            finishRun(run, buf, "failed", String.valueOf(t.getMessage()), true);
        }
    }

    /** 终态落库：run 行 + assistant 消息 + run_end 事件。 */
    private void finishRun(ChatRunDO run, RunBuffer buf, String status, String error,
                           boolean saveAssistantMessage) {
        run.setStatus(status);
        run.setAnswer(buf.answer);
        try {
            run.setToolEvents(buf.toolEvents.isEmpty() ? null : JSON.writeValueAsString(buf.toolEvents));
        } catch (Exception ignore) {
            run.setToolEvents(null);
        }
        run.setError(error);
        run.setFinishedAt(LocalDateTime.now());
        if (run.getPromptTokens() == null && buf.promptTokens > 0) {
            run.setPromptTokens((int) buf.promptTokens);
        }
        if (run.getCompletionTokens() == null && buf.completionTokens > 0) {
            run.setCompletionTokens((int) buf.completionTokens);
        }
        runMapper.updateById(run);

        if (saveAssistantMessage && (!buf.answer.isBlank() || !buf.toolEvents.isEmpty())) {
            appendMessage(run.getSessionId(), run.getId(), "assistant", buf.answer,
                    buf.toolEvents.isEmpty() ? null : serialize(buf.toolEvents));
        }
        synchronized (buf) {
            buf.status = status;
            buf.error = error;
            // Map.of 不接受 null（error 常态为 null），用 HashMap
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "run_end");
            end.put("status", status);
            end.put("error", error);
            buf.publish(end);
        }
        log.info("ChatRun {} → {}（answer {} 字符）", run.getId(), status, buf.answer.length());
    }

    /** 节流落库：500ms 间隔刷新部分答案。 */
    private void flushAnswer(long runId, RunBuffer buf) {
        long now = System.currentTimeMillis();
        if (now - buf.lastFlush < DB_FLUSH_INTERVAL_MS) {
            return;
        }
        buf.lastFlush = now;
        ChatRunDO patch = new ChatRunDO();
        patch.setId(runId);
        patch.setAnswer(buf.answer);
        try {
            patch.setToolEvents(buf.toolEvents.isEmpty() ? null : JSON.writeValueAsString(buf.toolEvents));
        } catch (Exception ignore) {
            // 保留原值
        }
        runMapper.updateById(patch);
    }

    /** 上下文组装：取最近 N 条持久化消息（服务端为事实源）。 */
    private List<Map<String, Object>> buildContext(Long sessionId, String question) {
        List<ChatMessageDO> rows = messageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .orderByDesc(ChatMessageDO::getId)
                .last("LIMIT " + CONTEXT_WINDOW));
        java.util.Collections.reverse(rows);
        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatMessageDO m : rows) {
            if ((m.getContent() != null && !m.getContent().isBlank()) || "user".equals(m.getRole())) {
                history.add(Map.of("role", m.getRole(), "content",
                        m.getContent() == null ? "" : m.getContent()));
            }
        }
        while (!history.isEmpty() && "assistant".equals(history.get(history.size() - 1).get("role"))) {
            history.remove(history.size() - 1);
        }
        boolean endsWithQuestion = !history.isEmpty()
                && question.equals(history.get(history.size() - 1).get("content"));
        if (!endsWithQuestion) {
            history.add(Map.of("role", "user", "content", question));
        }
        return history;
    }

    private String sessionSystemPrompt(Long sessionId) {
        ChatSessionDO s = sessionMapper.selectById(sessionId);
        return s == null ? null : s.getSystemPrompt();
    }

    private void appendMessage(Long sessionId, Long runId, String role, String content, String toolEventsJson) {
        ChatMessageDO m = new ChatMessageDO();
        m.setSessionId(sessionId);
        m.setRunId(runId);
        m.setRole(role);
        m.setContent(content == null ? "" : content);
        m.setToolEvents(toolEventsJson);
        m.setStatus("done");
        messageMapper.insert(m);
        ChatSessionDO s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setMessageCount((s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
    }

    private String liveSnapshotPayload(RunBuffer buf, long runId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "snapshot");
        p.put("run_id", runId);
        p.put("status", buf.status);
        p.put("answer", buf.answer);
        p.put("tool_events", List.copyOf(buf.toolEvents));
        p.put("error", buf.error);
        return serialize(p);
    }

    private String snapshotPayload(ChatRunDO run) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "snapshot");
        p.put("run_id", run.getId());
        p.put("status", run.getStatus());
        p.put("answer", run.getAnswer() == null ? "" : run.getAnswer());
        p.put("tool_events", parseToolEvents(run.getToolEvents()));
        p.put("error", run.getError());
        return serialize(p);
    }

    private static List<Map<String, Object>> parseToolEvents(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = JSON.readValue(json, List.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String serialize(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RunCancelled extends RuntimeException {
    }
}
