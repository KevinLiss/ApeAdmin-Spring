package com.apeadmin.framework.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * MP 手动装配（SB4 模块化自动配置与 MP 3.5.18 的 DataSource 候选评估时序不兼容，
 * starter 的 MybatisPlusAutoConfiguration 在 SB4.1 下不触发——此处以显式 Bean 装配，
 * 是 MP 官方认可的手动装配套路）。
 *
 * <p>拦截器链顺序对齐 RVP：数据权限 → 分页 → 乐观锁。
 */
@Configuration(proxyBeanMethods = false)
@Import({AuditMetaObjectHandler.class, DataPermissionAspect.class})
public class FrameworkMybatisAutoConfiguration {

    /** MP 拦截器链（Provider 延迟到查询期解析，断开创建期循环）。 */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            org.springframework.beans.factory.ObjectProvider<DataScopeProvider> provider) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(
                new ApeDataPermissionHandler(provider)));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQLITE));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /** SqlSessionFactory：手动装配（含 MP 全局配置 / 驼峰映射 / 审计填充 / 拦截器）。 */
    @Bean
    public MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource,
                                                          MybatisPlusInterceptor interceptor,
                                                          AuditMetaObjectHandler auditHandler) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(interceptor);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // SQL 日志（诊断期开启；由 logging.level 控制降噪后再移除）
        configuration.setLogImpl(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        globalConfig.setMetaObjectHandler(auditHandler);
        factory.setGlobalConfig(globalConfig);

        // mapper XML 目录（当前无 XML，占位）
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        return factory;
    }
}
