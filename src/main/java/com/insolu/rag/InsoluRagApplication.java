package com.insolu.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InsoluRagApplication {

    public static void main(String[] args) {
        // 指定 LangChain4j 使用 OkHttp HTTP 客户端（避免 Windows JDK HTTP 客户端兼容问题）
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.okhttp.OkHttpClientBuilderFactory");
        SpringApplication.run(InsoluRagApplication.class, args);
    }
}
