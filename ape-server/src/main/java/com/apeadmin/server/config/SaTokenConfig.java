package com.apeadmin.server.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.apeadmin.common.constant.ApiConst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 装配：JWT Simple 模式（踢人/黑名单全语义）+ 路由鉴权。
 *
 * <p>注意：SB4 下 application.yml 的 sa-token.* 段存在未生效问题（待查 ConfigData 兼容性），
 * 故配置以代码方式声明（sa-token 官方：存在 SaTokenConfig Bean 时自动接管全局配置）。
 */
@Configuration(proxyBeanMethods = false)
public class SaTokenConfig {

    @Value("${APE_JWT_SECRET:apeadmin-dev-secret-change-me-in-prod-2026}")
    private String jwtSecret;

    /** 全局配置（代码声明，@Primary 优先于 starter 从 yml 绑定的默认 Bean）。 */
    @Bean
    @org.springframework.context.annotation.Primary
    public cn.dev33.satoken.config.SaTokenConfig saTokenGlobalConfig() {
        cn.dev33.satoken.config.SaTokenConfig cfg = new cn.dev33.satoken.config.SaTokenConfig();
        cfg.setTokenName("Authorization");
        cfg.setTokenPrefix("Bearer");
        cfg.setIsConcurrent(true);
        cfg.setIsShare(false);
        cfg.setIsReadCookie(false);
        cfg.setTimeout(86400);          // access 24h
        cfg.setActiveTimeout(-1);
        cfg.setIsPrint(false);
        cfg.setIsLog(false);
        cfg.setJwtSecretKey(jwtSecret);
        return cfg;
    }

    /** 会话存储 Dao：显式单实例（修 login 写入与校验读取实例分裂问题；M5 换 Redis Dao 实现）。 */
    @Bean
    public cn.dev33.satoken.dao.SaTokenDao saTokenDao() {
        return new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl();
    }

    /** JWT-Simple：JWT 形态 token，映射仍写 Dao → 踢人下线/黑名单全语义可用。 */
    @Bean
    public StpLogic stpLogicJwtForSimple() {
        StpLogicJwtForSimple logic = new StpLogicJwtForSimple();
        // SB4 下 starter 的 Bean 收集不稳定，启动时显式接管（官方多账号体系 API）
        cn.dev33.satoken.stp.StpUtil.setStpLogic(logic);
        return logic;
    }

    @jakarta.annotation.PostConstruct
    public void forceInit() {
        // 二次保险：确保 SaManager 全局配置为本进程唯一配置
        cn.dev33.satoken.SaManager.setConfig(saTokenGlobalConfig());
    }

    @Bean
    public WebMvcConfigurer saTokenWebConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SaInterceptor(handle ->
                                SaRouter.match(ApiConst.API_PREFIX + "/**")
                                        .notMatch(
                                                ApiConst.API_PREFIX + "/auth/login",
                                                ApiConst.API_PREFIX + "/auth/refresh",
                                                ApiConst.API_PREFIX + "/settings/public",
                                                ApiConst.API_PREFIX + "/health",
                                                ApiConst.API_PREFIX + "/i18n/**",
                                                ApiConst.API_PREFIX + "/setup/**"
                                        )
                                        .check(r -> StpUtil.checkLogin())))
                        .addPathPatterns(ApiConst.API_PREFIX + "/**");
            }
        };
    }
}
