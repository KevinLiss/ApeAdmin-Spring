package com.apeadmin.framework.log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * framework-log 自动装配入口（事件消费方由业务模块提供；@Async 由 server 层 @EnableAsync 开启）。
 */
@Configuration(proxyBeanMethods = false)
public class FrameworkLogAutoConfiguration {

    @Bean
    public static OperateLogAspect operateLogAspect(
            org.springframework.context.ApplicationEventPublisher publisher) {
        return new OperateLogAspect(publisher);
    }
}
