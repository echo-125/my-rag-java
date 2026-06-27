package com.insolu.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class InsoluRagApplication {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("activeLlmConfig", "activeEmbeddingConfig", "ragConfigCache");
    }

    public static void main(String[] args) {
        // 指定 LangChain4j 使用 OkHttp HTTP 客户端（避免 Windows JDK HTTP 客户端兼容问题）
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.okhttp.OkHttpClientBuilderFactory");
        SpringApplication.run(InsoluRagApplication.class, args);
    }
}
