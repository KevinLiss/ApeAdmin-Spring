package com.apeadmin.plugin.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L3 MCP 外挂客户端：stdio JSON-RPC 2.0（MCP 协议最小面）。
 *
 * <p>spawn 外部 MCP server 子进程（command + args），按 MCP 规范走：
 * initialize → notifications/initialized → tools/list → tools/call。
 * 每请求同步等待响应（id 配对；单进程串行调用，管理面板场景足够）。
 *
 * <p>config 约定：{"command": "...", "args": [...], "env": {...}}。
 */
@Slf4j
@Component
public class StdioMcpClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TIMEOUT_MS = 30_000;

    private final AtomicLong idSeq = new AtomicLong(1);
    /** serverName → 进程会话。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    record ProcessIO(Process process, BufferedReader reader, OutputStream writer) {
    }

    static final class Session {
        final ProcessIO io;

        Session(ProcessIO io) {
            this.io = io;
        }
    }

    /** 启动外挂 server 并 initialize，返回其工具清单 [{name, description, inputSchema}]。 */
    public synchronized List<Map<String, Object>> start(String serverName, String command,
                                                        List<String> args, Map<String, String> env)
            throws Exception {
        if (sessions.containsKey(serverName)) {
            throw new IllegalStateException("外挂 server 已在运行: " + serverName);
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(command, args));
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Session s = new Session(new ProcessIO(p, reader, p.getOutputStream()));
        sessions.put(serverName, s);

        try {
            // initialize（握手）
            ObjectNode init = JSON.createObjectNode();
            init.put("jsonrpc", "2.0");
            init.put("id", idSeq.getAndIncrement());
            init.put("method", "initialize");
            ObjectNode params = init.putObject("params");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "apeadmin-spring");
            clientInfo.put("version", "1.0.0");
            JsonNode resp = request(s, init);
            if (resp.has("error")) {
                throw new IllegalStateException("initialize 失败: " + resp.get("error"));
            }
            // initialized 通知
            ObjectNode ready = JSON.createObjectNode();
            ready.put("jsonrpc", "2.0");
            ready.put("method", "notifications/initialized");
            write(s, ready);

            // tools/list
            ObjectNode listReq = JSON.createObjectNode();
            listReq.put("jsonrpc", "2.0");
            listReq.put("id", idSeq.getAndIncrement());
            listReq.put("method", "tools/list");
            JsonNode tools = request(s, listReq).path("result").path("tools");

            List<Map<String, Object>> out = new ArrayList<>();
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", t.path("name").asText());
                    m.put("description", t.path("description").asText(""));
                    JsonNode schema = t.path("inputSchema");
                    m.put("input_schema", schema.isMissingNode()
                            ? Map.of("type", "object", "properties", Map.of())
                            : JSON.treeToValue(schema, Map.class));
                    out.add(m);
                }
            }
            log.info("L3 外挂 {} 已连接：{} 个工具", serverName, out.size());
            return out;
        } catch (Exception e) {
            stop(serverName);
            throw e;
        }
    }

    /** 调用外挂工具，返回 content 文本（MCP tool result.content[].text 拼接）。 */
    public synchronized String callTool(String serverName, String toolName, String argumentsJson)
            throws Exception {
        Session s = sessions.get(serverName);
        if (s == null) {
            throw new IllegalStateException("外挂 server 未运行: " + serverName);
        }
        ObjectNode req = JSON.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", idSeq.getAndIncrement());
        req.put("method", "tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", toolName);
        params.set("arguments", argumentsJson == null || argumentsJson.isBlank()
                ? JSON.createObjectNode() : JSON.readTree(argumentsJson));

        JsonNode resp = request(s, req);
        if (resp.has("error")) {
            throw new IllegalStateException("外挂工具调用失败: " + resp.get("error"));
        }
        JsonNode content = resp.path("result").path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode c : content) {
                if (c.path("type").asText("").equals("text")) {
                    sb.append(c.path("text").asText());
                }
            }
        }
        return sb.length() > 0 ? sb.toString()
                : JSON.writeValueAsString(resp.path("result"));
    }

    /** 停止外挂 server。 */
    public synchronized boolean stop(String serverName) {
        Session s = sessions.remove(serverName);
        if (s == null) {
            return false;
        }
        s.io.process().destroy();
        log.info("L3 外挂 {} 已停止", serverName);
        return true;
    }

    public synchronized boolean isRunning(String serverName) {
        Session s = sessions.get(serverName);
        return s != null && s.io.process().isAlive();
    }

    // ---------------------------------------------------------------- 内部

    private JsonNode request(Session s, ObjectNode req) throws Exception {
        write(s, req);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        long id = req.path("id").asLong();
        while (System.currentTimeMillis() < deadline) {
            String line = s.io.reader().readLine();   // 阻塞读一行（同步等待）
            if (line == null) {
                throw new IllegalStateException("外挂进程输出已关闭");
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith(":")) {
                continue;   // SSE 注释/心跳
            }
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (Exception ignore) {
                continue;
            }
            if (node.path("id").asLong(-1) == id) {
                return node;
            }
            // 其余消息（通知/并发响应）丢弃（管理面板单请求串行）
        }
        throw new java.util.concurrent.TimeoutException("外挂响应超时");
    }

    private void write(Session s, ObjectNode req) throws Exception {
        OutputStream out = s.io.writer();
        out.write((JSON.writeValueAsString(req) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static List<String> buildCommand(String command, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        if (args != null) {
            cmd.addAll(args);
        }
        return cmd;
    }
}
