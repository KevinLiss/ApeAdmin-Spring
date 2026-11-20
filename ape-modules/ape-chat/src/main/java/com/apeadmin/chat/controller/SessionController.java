package com.apeadmin.chat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.apeadmin.common.api.R;
import com.apeadmin.chat.service.SessionService;
import com.apeadmin.system.security.UserContextHolder;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** AI 会话管理（对齐前端 /ai/sessions 契约；全部按属主过滤）。 */
@RestController
@RequestMapping("/api/v1/ai/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Data
    public static class SessionReq {
        private String title;
    }

    @Data
    public static class AppendMessageReq {
        private String role;
        private String content;
        private List<Map<String, Object>> tool_events;
    }

    @SaCheckLogin
    @GetMapping
    public R<Map<String, Object>> list() {
        return R.ok(sessionService.list(UserContextHolder.require().id()));
    }

    @SaCheckLogin
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody(required = false) SessionReq body) {
        return R.ok(sessionService.create(UserContextHolder.require().id(),
                body == null ? null : body.getTitle()), "创建成功");
    }

    @SaCheckLogin
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return R.ok(sessionService.get(id, UserContextHolder.require().id()));
    }

    @SaCheckLogin
    @PutMapping("/{id}")
    public R<Map<String, Object>> rename(@PathVariable Long id, @RequestBody SessionReq body) {
        return R.ok(sessionService.rename(id, UserContextHolder.require().id(), body.getTitle()), "重命名成功");
    }

    @SaCheckLogin
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sessionService.delete(id, UserContextHolder.require().id());
        return R.ok(null, "删除成功");
    }

    @SaCheckLogin
    @PostMapping("/{id}/messages")
    public R<Map<String, Object>> appendMessage(@PathVariable Long id, @RequestBody AppendMessageReq body) {
        return R.ok(sessionService.appendMessage(id, UserContextHolder.require().id(),
                body.getRole(), body.getContent(), body.getTool_events()), "消息已保存");
    }
}
