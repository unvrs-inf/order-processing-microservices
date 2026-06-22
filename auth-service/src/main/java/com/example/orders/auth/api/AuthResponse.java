package com.example.orders.auth.api;

/**
 * Общая структура ответа на регистрацию и вход.
 * @param accessToken подписанный токен доступа
 * @param tokenType тип токена, {@code Bearer}
 * @param expiresIn срок действия в секундах
 * @param user учётная запись пользователя
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    /** Тип выдаваемого токена. */
    public static final String BEARER = "Bearer";

    /**
     * @param accessToken подписанный токен
     * @param expiresIn срок действия в секундах
     * @param user учётная запись пользователя
     * @return ответ с токеном Bearer
     */
    public static AuthResponse bearer(String accessToken, long expiresIn, UserResponse user) {
        return new AuthResponse(accessToken, BEARER, expiresIn, user);
    }
}
