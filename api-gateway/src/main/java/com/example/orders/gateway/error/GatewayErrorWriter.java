package com.example.orders.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Записывает {@link ApiError} прямо в реактивный HTTP-ответ. Ошибки шлюза не обрабатываются контроллерами сервисов.
 */
@Component
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param exchange текущий обмен с ещё не отправленным ответом
     * @param status статус
     * @param message описание для клиента
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        return write(exchange.getResponse(), exchange.getRequest().getPath().value(), status, message);
    }

    /**
     * @param response ответ, в который нужно записать тело; может быть декоратором
     * @param path URI запроса
     * @param status статус
     * @param message описание для клиента
     */
    public Mono<Void> write(ServerHttpResponse response, String path, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(serialise(status, message, path));
        return response.writeWith(Mono.just(buffer))
                .doOnError(error -> DataBufferUtils.release(buffer));
    }

    private byte[] serialise(HttpStatus status, String message, String path) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
        try {
            return objectMapper.writeValueAsBytes(body);
        }
        catch (JsonProcessingException e) {
            // Обработчик ошибки должен завершить ответ даже при сбое сериализации.
            return ("{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
