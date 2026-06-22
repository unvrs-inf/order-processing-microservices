package com.example.orders.gateway.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Ответ об ошибке, сформированный шлюзом для 401, 403 или 429.
 * @param timestamp время формирования записи
 * @param status статус
 * @param error текст HTTP-статуса
 * @param message описание для клиента
 * @param path URI запроса
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
