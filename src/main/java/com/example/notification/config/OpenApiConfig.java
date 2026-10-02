package com.example.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Multi-Tenant Notification Service")
                .version("0.1.0")
                .description("Ingest notifications (tenant via X-Tenant-Id) and manage templates."));
    }
}
