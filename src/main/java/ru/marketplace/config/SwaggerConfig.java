package ru.marketplace.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Без @EnableWebMvc (в anyforms он стоял здесь): он выключает автоконфиг Spring MVC,
 * и Jackson начинает отдавать Instant числом, а контракт API — ISO-строки.
 * Адрес сервера не задаём — springdoc берёт его из запроса.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("marketplace")
                                .description("Документация API")
                                .version("1.0.0"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "Bearer",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
    }
}
