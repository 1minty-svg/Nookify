package com.nookify.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",    // Vite
                        "http://127.0.0.1:5500",    // VS Code Live Server
                        "http://localhost:8000",    // Python http.server
                        "*"                         // Для локальной отладки (в продакшене убрать!)
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "Content-Length", "Access-Control-Allow-Origin")
                .allowCredentials(false)
                .maxAge(3600); // Кэшируем preflight-запросы на 1 час
    }
}
