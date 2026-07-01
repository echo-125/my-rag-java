package com.he.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端路由配置。
 * 支持 legacy / both / new 三种模式，控制新旧前端的访问入口。
 *
 * 模式说明：
 * - legacy: / → 旧前端（static/index.html）
 * - both:   / → 旧前端，/app/ → 新前端
 * - new:    / → 新前端，/app/ → 新前端（兼容 bookmark）
 */
@Configuration
public class FrontendRoutingConfig implements WebMvcConfigurer {

    @Value("${rag.frontend.mode:both}")
    private String mode;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        switch (mode) {
            case "legacy" -> {
                registry.addResourceHandler("/**")
                        .addResourceLocations("classpath:/static/");
            }
            case "both" -> {
                // /app/** → 新前端构建产物
                registry.addResourceHandler("/app/**")
                        .addResourceLocations("classpath:/static/app/");
                // / → 旧前端（默认 static/ 下的 index.html）
                registry.addResourceHandler("/**")
                        .addResourceLocations("classpath:/static/");
            }
            case "new" -> {
                // /assets/** → 新前端资源（base: '/' 时资源在 assets/ 下）
                registry.addResourceHandler("/assets/**")
                        .addResourceLocations("classpath:/static/app/assets/");
                // /favicon.svg 等根目录静态文件
                registry.addResourceHandler("/favicon.svg", "/favicon.ico")
                        .addResourceLocations("classpath:/static/app/");
                // /app/** → 也指向新前端（兼容 bookmark）
                registry.addResourceHandler("/app/**")
                        .addResourceLocations("classpath:/static/app/");
            }
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if ("new".equals(mode)) {
            // SPA fallback：所有非 API、非静态资源的 GET 请求 → index.html
            registry.addViewController("/").setViewName("forward:/index.html");
            registry.addViewController("/{path:[^\\.]*}")
                    .setViewName("forward:/index.html");
        }
        if ("both".equals(mode) || "new".equals(mode)) {
            registry.addViewController("/app")
                    .setViewName("forward:/app/index.html");
            registry.addViewController("/app/{path:[^\\.]*}")
                    .setViewName("forward:/app/index.html");
        }
    }
}
