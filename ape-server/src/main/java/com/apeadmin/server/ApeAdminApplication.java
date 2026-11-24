package com.apeadmin.server;

import com.apeadmin.framework.log.FrameworkLogAutoConfiguration;
import com.apeadmin.framework.mybatis.FrameworkMybatisAutoConfiguration;
import com.apeadmin.framework.web.FrameworkWebAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ApeAdmin-Spring 启动入口。
 * 虚拟线程由 spring.threads.virtual.enabled=true 全局开启（SSE/异步/Run worker）。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.apeadmin")
@ConfigurationPropertiesScan(basePackages = "com.apeadmin")
@MapperScan("com.apeadmin.**.mapper")
@Import({FrameworkWebAutoConfiguration.class, FrameworkMybatisAutoConfiguration.class,
        FrameworkLogAutoConfiguration.class})
public class ApeAdminApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ApeAdminApplication.class);
        // sa-token 1.46 的 SaBeanRegister 在容器就绪后会以全新实例覆盖 SaManager 全局状态
        // （config/dao/stpLogic 实例分裂），导致登录会话写入与校验读取不一致——
        // 排除之，改由 SaTokenConfig 显式接管（config/dao/stpLogic 手动注册）。
        app.setDefaultProperties(java.util.Map.of(
                "spring.autoconfigure.exclude", "cn.dev33.satoken.spring.SaBeanRegister"));
        app.run(args);
    }
}
