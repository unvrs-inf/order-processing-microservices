package com.example.orders.gateway.security;

/** Заголовки, которыми управляет шлюз. */
public final class GatewayHeaders {

    /** Идентификатор корреляции запроса и ответа. Генерируется при отсутствующем или неверном значении. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Проверенный идентификатор пользователя из поля {@code sub}. */
    public static final String USER_ID = "X-User-Id";

    /** Роли пользователя через запятую, без префикса {@code ROLE_}. */
    public static final String USER_ROLES = "X-User-Roles";

    private GatewayHeaders() {
    }
}
