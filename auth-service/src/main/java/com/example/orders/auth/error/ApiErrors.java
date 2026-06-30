package com.example.orders.auth.error;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Общая фабрика для заполнения {@link ApiError}. */
public final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * @param status HTTP-статус
     * @param message описание для клиента
     * @param path URI запроса
     * @return тело ошибки без ошибок отдельных полей
     */
    public static ApiError of(HttpStatus status, String message, String path) {
        return of(status, message, path, null);
    }

    /**
     * @param status HTTP-статус
     * @param message описание для клиента
     * @param path URI запроса
     * @param fieldErrors ошибки полей; пустое значение опускает ключ
     * @return готовое тело ошибки
     */
    public static ApiError of(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
