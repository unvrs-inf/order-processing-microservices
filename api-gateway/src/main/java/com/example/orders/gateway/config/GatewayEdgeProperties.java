package com.example.orders.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки шлюза для проверки JWT и правил CORS.
 * @param security параметры проверки JWT
 * @param cors разрешённые источники браузерных запросов
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayEdgeProperties(Security security, Cors cors) {

    /**
     * @param jwksUri адрес набора ключей auth-service; ключи загружаются по запросу и кешируются
     * @param issuer ожидаемое значение поля {@code iss}
     */
    public record Security(
            @DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri,
            @DefaultValue("order-processing-auth") String issuer) {
    }

    /**
     * @param allowedOrigins точные источники с указанием схемы, хоста и порта
     */
    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }
}
