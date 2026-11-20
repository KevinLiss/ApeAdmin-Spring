package com.apeadmin.framework.web;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 输出辅助：统一 data: 行写入（对齐前端逐行解析契约）+ 心跳注释。
 */
public final class SseHelper {

    private SseHelper() {
    }

    /** 按前端契约写一行 data: {json}（事件类型在 JSON 的 type 字段内，前端不读 event: 行）。 */
    public static void send(SseEmitter emitter, String json) throws IOException {
        emitter.send(SseEmitter.event().data(json));
    }

    /** 心跳注释行（前端对非 data: 行天然忽略）。 */
    public static void ping(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().comment("ping"));
    }
}
