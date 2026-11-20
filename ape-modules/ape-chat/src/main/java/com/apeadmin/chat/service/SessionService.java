package com.apeadmin.chat.service;

import com.apeadmin.chat.domain.ChatMessageDO;
import com.apeadmin.chat.domain.ChatSessionDO;
import com.apeadmin.chat.mapper.ChatMessageMapper;
import com.apeadmin.chat.mapper.ChatSessionMapper;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话与消息管理（对齐 Python 版 chat_session.py 契约）。 */
@Service
public class SessionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    public SessionService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    /** 当前用户会话列表（最新在前）。 */
    public Map<String, Object> list(Long userId) {
        List<ChatSessionDO> items = sessionMapper.selectList(Wrappers.<ChatSessionDO>lambdaQuery()
                .eq(ChatSessionDO::getUserId, userId)
                .orderByDesc(ChatSessionDO::getUpdatedAt)
                .orderByDesc(ChatSessionDO::getId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items.stream().map(this::toOut).toList());
        return out;
    }

    public Map<String, Object> create(Long userId, String title) {
        ChatSessionDO s = new ChatSessionDO();
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? "新对话" : title.strip().substring(0, Math.min(200, title.strip().length())));
        s.setMessageCount(0);
        sessionMapper.insert(s);
        return toOut(s);
    }

    /** 会话详情 + 全部消息（时间序）。 */
    public Map<String, Object> get(Long sessionId, Long userId) {
        ChatSessionDO s = owned(sessionId, userId);
        List<ChatMessageDO> messages = messageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getSessionId, sessionId)
                .orderByAsc(ChatMessageDO::getId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", toOut(s));
        out.put("messages", messages.stream().map(this::messageOut).toList());
        return out;
    }

    public Map<String, Object> rename(Long sessionId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标题不能为空");
        }
        ChatSessionDO s = owned(sessionId, userId);
        s.setTitle(title.strip().substring(0, Math.min(200, title.strip().length())));
        sessionMapper.updateById(s);
        return toOut(s);
    }

    public void delete(Long sessionId, Long userId) {
        ChatSessionDO s = owned(sessionId, userId);
        messageMapper.delete(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getSessionId, sessionId));
        sessionMapper.deleteById(s.getId());
    }

    /** 追加消息（前端直发场景）。 */
    public Map<String, Object> appendMessage(Long sessionId, Long userId, String role, String content,
                                             List<Map<String, Object>> toolEvents) {
        ChatSessionDO s = owned(sessionId, userId);
        ChatMessageDO m = new ChatMessageDO();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content == null ? "" : content);
        m.setToolEvents(toolEvents == null || toolEvents.isEmpty() ? null : serialize(toolEvents));
        m.setStatus("done");
        messageMapper.insert(m);
        s.setMessageCount((s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
        sessionMapper.updateById(s);
        return messageOut(m);
    }

    public ChatSessionDO owned(Long sessionId, Long userId) {
        ChatSessionDO s = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    private Map<String, Object> toOut(ChatSessionDO s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        m.put("provider_id", s.getProviderId());
        m.put("model", s.getModel());
        m.put("message_count", s.getMessageCount());
        return m;
    }

    private Map<String, Object> messageOut(ChatMessageDO m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("session_id", m.getSessionId());
        out.put("role", m.getRole());
        out.put("content", m.getContent());
        out.put("tool_events", parseToolEvents(m.getToolEvents()));
        return out;
    }

    private static List<Map<String, Object>> parseToolEvents(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> list = JSON.readValue(json, List.class);
            return list == null || list.isEmpty() ? null : list;
        } catch (Exception e) {
            return null;
        }
    }

    private static String serialize(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
