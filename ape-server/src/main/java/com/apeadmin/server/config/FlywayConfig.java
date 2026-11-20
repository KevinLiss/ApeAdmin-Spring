package com.apeadmin.server.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway 手动装配（SB4 模块化后 Flyway 自动配置未随 starter-web 链路触发）。
 * Bean 创建即执行 migrate（initMethod），确保任何 DB 查询前 schema 就绪。
 * V1.1 种子含全量权限菜单——修 Python 版"首装缺权限"缺陷。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
