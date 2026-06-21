package com.example.orders.support.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Общий ответ 403 для пользователя без требуемого права. */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    /** Типовое сообщение для отказа авторизации. */
    public static final String MESSAGE = "Access is denied";

    private final ApiErrorHttpWriter writer;

    /**
     * @param writer компонент записи общего тела ошибки
     */
    public RestAccessDeniedHandler(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, MESSAGE);
    }
}
