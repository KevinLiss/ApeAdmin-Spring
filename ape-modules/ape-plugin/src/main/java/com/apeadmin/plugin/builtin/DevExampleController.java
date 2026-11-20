package com.apeadmin.plugin.builtin;

import com.apeadmin.common.api.R;
import com.apeadmin.plugin.mapper.DevExampleNoteMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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

/**
 * dev_example 插件 REST API（权限 dev_example:*）。
 *
 * <p>示例插件页面路由：/api/v1/dev_example/notes CRUD——演示插件自带业务端点
 * （L1 插件的控制器与核心模块同容器编译期装配）。
 */
@RestController
@RequestMapping("/api/v1/dev_example/notes")
@RequiredArgsConstructor
public class DevExampleController {

    private final DevExampleNoteMapper noteMapper;

    @Data
    public static class NoteReq {
        private String title;
        private String content;
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("dev_example:list")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        List<DevExampleNote> notes = noteMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<DevExampleNote>lambdaQuery()
                        .orderByDesc(DevExampleNote::getId));
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (DevExampleNote n : notes) {
            items.add(Map.of("id", n.getId(), "title", n.getTitle(),
                    "content", n.getContent() == null ? "" : n.getContent()));
        }
        return R.ok(items);
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("dev_example:list")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        DevExampleNote n = noteMapper.selectById(id);
        if (n == null) {
            return R.fail(com.apeadmin.common.api.ErrorCode.NOT_FOUND, "备忘录不存在");
        }
        return R.ok(Map.of("id", n.getId(), "title", n.getTitle(),
                "content", n.getContent() == null ? "" : n.getContent()));
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("dev_example:add")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody NoteReq body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return R.fail(com.apeadmin.common.api.ErrorCode.BAD_REQUEST, "标题必填");
        }
        DevExampleNote n = new DevExampleNote();
        n.setTitle(body.getTitle());
        n.setContent(body.getContent());
        noteMapper.insert(n);
        return R.ok(Map.of("id", n.getId()), "创建成功");
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("dev_example:add")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody NoteReq body) {
        DevExampleNote n = noteMapper.selectById(id);
        if (n == null) {
            return R.fail(com.apeadmin.common.api.ErrorCode.NOT_FOUND, "备忘录不存在");
        }
        if (body.getTitle() != null) {
            n.setTitle(body.getTitle());
        }
        if (body.getContent() != null) {
            n.setContent(body.getContent());
        }
        noteMapper.updateById(n);
        return R.ok(null, "更新成功");
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("dev_example:add")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        noteMapper.deleteById(id);
        return R.ok(null, "删除成功");
    }
}
