package com.apeadmin.server.config;

import com.apeadmin.common.constant.ApiConst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 静态托管（前端复用契约）：
 * /admin/** ← classpath:/static/admin（Vue history 路由 fallback 到 index.html）
 * /uploads/** ← 磁盘上传目录
 * / → 302 /admin/
 */
@Configuration(proxyBeanMethods = false)
public class WebStaticConfig implements WebMvcConfigurer {

    @Value("${ape.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(ApiConst.ADMIN_PATH + "/**")
                .addResourceLocations("classpath:/static/admin/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 空路径（/admin/ 本体）→ index.html；目录资源直接返回会抛异常（实证 500）
                        if (resourcePath == null || resourcePath.isBlank() || resourcePath.endsWith("/")) {
                            return new ClassPathResource("/static/admin/index.html");
                        }
                        Resource requested = location.createRelative(resourcePath);
                        boolean isDirectory = false;
                        try {
                            isDirectory = requested.getFile().isDirectory();
                        } catch (IOException ignore) {
                            // jar 内资源无 File 形态，按存在性判断
                        }
                        if (isDirectory) {
                            return new ClassPathResource("/static/admin/index.html");
                        }
                        // 其余：真实资源存在则返回；不存在 → SPA history fallback
                        return requested.exists() && requested.isReadable()
                                ? requested
                                : new ClassPathResource("/static/admin/index.html");
                    }
                });

        registry.addResourceHandler(ApiConst.UPLOADS_PREFIX + "/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", ApiConst.ADMIN_PATH + "/");
        // 精确路径直出 SPA 入口：/admin 与 /admin/ 的剩余路径为空/null 时，
        // PathResourceResolver 不会被调用（实证 2026-11-20：resolver 链返回 null → NoResourceFoundException），
        // 故用 ViewController（优先级高于资源处理器）forward 到真实文件。
        registry.addViewController(ApiConst.ADMIN_PATH).setViewName("forward:/admin/index.html");
        registry.addViewController(ApiConst.ADMIN_PATH + "/").setViewName("forward:/admin/index.html");
    }
}
