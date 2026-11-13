package com.sarikaya.e_ticaret.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API dokümanı (OpenAPI 3). Uçlar koddan otomatik çıkarılır; Swagger UI'da "Authorize" ile
 * giriş token'ı verilip istekler denenebilir: http://localhost:8080/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {
    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI eTicaretOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-ticaret API")
                        .version("1")
                        .description("Mağaza, sepet, sipariş, ödeme ve admin uçları. Ödemenin kendisi iyzico-service'tedir."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
