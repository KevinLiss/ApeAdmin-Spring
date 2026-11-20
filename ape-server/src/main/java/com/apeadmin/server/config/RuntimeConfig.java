package com.apeadmin.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;

/**
 * 运行时目录准备。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeConfig {

    @Value("${ape.upload-dir:./uploads}")
    private String uploadDir;

    @PostConstruct
    public void prepareDirs() {
        File dir = new File(uploadDir);
        if (!dir.exists() && dir.mkdirs()) {
            org.slf4j.LoggerFactory.getLogger(RuntimeConfig.class)
                    .info("已创建上传目录: {}", dir.getAbsolutePath());
        }
    }
}
