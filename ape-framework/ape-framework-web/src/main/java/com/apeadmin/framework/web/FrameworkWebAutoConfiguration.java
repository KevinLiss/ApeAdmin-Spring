package com.apeadmin.framework.web;

import com.apeadmin.common.api.R;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * framework-web 自动装配入口。
 */
@Configuration(proxyBeanMethods = false)
@Import(GlobalExceptionHandler.class)
public class FrameworkWebAutoConfiguration {

    @Bean
    public WebMvcConfigurer frameworkWebDefaults() {
        return new WebMvcConfigurer() {
            // CORS 由 server 层按部署形态配置；此处预留统一扩展点
        };
    }
}
