package com.example.orders.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки JWT и создания администратора при запуске.
 * @param jwt параметры токенов RS256
 * @param admin учётная запись администратора по умолчанию
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Jwt jwt, Admin admin) {

    /**
     * @param issuer значение поля {@code iss}, проверяемое при чтении токена
     * @param expiration срок действия токена
     * @param privateKey закрытый ключ PKCS#8 PEM; пустое значение включает временную пару
     * @param publicKey открытый ключ X.509 PEM; пустое значение включает временную пару
     * @param keyId идентификатор {@code kid}; по умолчанию случайный UUID
     */
    public record Jwt(
            String issuer,
            Duration expiration,
            String keyId,
            String privateKey,
            String publicKey) {
    }

    /**
     * @param email нормализуемая почта администратора
     * @param password пароль администратора
     * @param name отображаемое имя
     */
    public record Admin(String email, String password, String name) {
    }
}
