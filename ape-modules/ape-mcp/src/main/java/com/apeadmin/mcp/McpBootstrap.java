package com.apeadmin.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * MCP 启动装配：先挂持久化（sys_mcp_tool upsert 生效），再注册内置项。
 * 顺序敏感：registerAll() 内每次 registerTool 都会触发 upsertTool。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class McpBootstrap implements ApplicationRunner {

    private final McpManager manager;
    private final McpBuiltins builtins;
    private final McpService service;

    @Override
    public void run(ApplicationArguments args) {
        manager.setPersistence(service);
        builtins.registerAll();
        log.info("MCP 网关就绪：{} 工具 / {} 资源 / {} 提示词",
                manager.listTools(null).size(), manager.listResources().size(),
                manager.listPrompts().size());
    }
}
