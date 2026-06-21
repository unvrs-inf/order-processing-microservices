package com.example.orders.support.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Общий формат ошибок контроллеров, валидации и Spring Security.
 * @param timestamp время формирования записи
 * @param status статус
 * @param error текст HTTP-статуса
 * @param message описание для клиента
 * @param path URI запроса
 * @param fieldErrors ошибки отдельных полей
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {
}
