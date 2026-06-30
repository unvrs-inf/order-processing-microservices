package com.example.orders.support.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Общий ответ 401 для отсутствующего, повреждённого или просроченного токена.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Сообщение совпадает с отказом auth-service и шлюза. */
    public static final String MESSAGE = "Authentication is required to access this resource";

    private final ApiErrorHttpWriter writer;

    /**
     * @param writer компонент записи общего тела ошибки
     */
    public RestAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        writer.write(request, response, HttpStatus.UNAUTHORIZED, MESSAGE);
    }
}
