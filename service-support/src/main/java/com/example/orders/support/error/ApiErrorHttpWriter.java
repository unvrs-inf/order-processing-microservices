package com.example.orders.support.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Записывает {@link ApiError} прямо в HTTP-ответ для обработчиков Spring Security.
 */
public class ApiErrorHttpWriter {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper JSON-маппер приложения
     */
    public ApiErrorHttpWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param request текущий запрос; в теле ошибки используется только его URI
     * @param response HTTP-ответ для записи
     * @param status статус
     * @param message описание для клиента
     * @throws IOException если запись HTTP-ответа завершилась ошибкой
     */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrors.of(status, message, request.getRequestURI()));
    }
}
