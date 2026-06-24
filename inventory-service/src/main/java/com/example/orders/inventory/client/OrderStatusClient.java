package com.example.orders.inventory.client;

import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Клиент inventory-service для чтения статуса заказа. При неверном или недоступном ответе запрещает освобождение. Токен администратора передаётся по запросу, а решение принимается только для проверенного CANCELLED или REJECTED.
 */
@Component
public class OrderStatusClient {

    /** Предельный размер ответа order-service для разбора. */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(OrderStatusClient.class);

    /** Известные статусы, в которых ручное освобождение запрещено. */
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "PENDING", "STOCK_RESERVED", "PAYMENT_PENDING", "CONFIRMED", "CANCELLATION_PENDING");

    private final RestClient restClient;
    private final ObjectMapper mapper;

    OrderStatusClient(RestClient orderRestClient, ObjectMapper mapper) {
        this.restClient = orderRestClient;
        this.mapper = mapper;
    }

    /**
     * @param orderId идентификатор заказа
     * @param bearerToken токен администратора
     * @return результат проверки
     * @throws IllegalArgumentException если отсутствует идентификатор заказа или токен
     */
    public OrderStatusVerdict check(UUID orderId, String bearerToken) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("a bearer token is required to ask order-service");
        }
        try {
            OrderStatusVerdict verdict = restClient.get()
                    .uri("/api/orders/{id}", orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(CorrelationId.HEADER, CorrelationId.sanitise(MDC.get(CorrelationId.MDC_KEY)))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> interpret(orderId, response));
            return verdict == null ? OrderStatusVerdict.UNVERIFIABLE : verdict;
        }
        catch (ResourceAccessException e) {
            // При тайм-ауте или обрыве записываем только тип ошибки, сохраняя токен вне логов.
            log.warn("order-service is not reachable for order {}: {}", orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNAVAILABLE;
        }
        catch (RestClientException e) {
            log.warn("order-service answer for order {} could not be processed: {}",
                    orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNVERIFIABLE;
        }
        catch (RuntimeException e) {
            log.warn("order-service lookup for order {} failed unexpectedly: {}",
                    orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNAVAILABLE;
        }
    }

    private OrderStatusVerdict interpret(UUID orderId, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return readVerdict(orderId, response);
        }
        OrderStatusVerdict verdict;
        if (status == 404) {
            verdict = OrderStatusVerdict.NOT_FOUND;
        }
        else if (status == 401 || status == 403) {
            verdict = OrderStatusVerdict.ACCESS_DENIED;
        }
        else if (status >= 500 || status == 408 || status == 429) {
            verdict = OrderStatusVerdict.UNAVAILABLE;
        }
        else {
            // Ожидаем ровно 200 с пригодным телом. Редиректы не выполняются.
            verdict = OrderStatusVerdict.UNVERIFIABLE;
        }
        log.warn("order-service answered {} for order {}: {}", status, orderId, verdict);
        return verdict;
    }

    private OrderStatusVerdict readVerdict(UUID orderId, ClientHttpResponse response) throws IOException {
        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            return unverifiable(orderId, "body is empty or larger than " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        }
        catch (IOException e) {
            return unverifiable(orderId, "body is not JSON");
        }
        if (root == null || !root.isObject()) {
            return unverifiable(orderId, "body is not a JSON object");
        }
        JsonNode id = root.get("id");
        if (id == null || !id.isTextual() || !orderId.toString().equalsIgnoreCase(id.asText())) {
            // Ответ должен содержать идентификатор запрошенного заказа.
            return unverifiable(orderId, "body does not describe the requested order");
        }
        JsonNode state = root.get("status");
        if (state == null || !state.isTextual()) {
            return unverifiable(orderId, "status is missing or not a string");
        }
        String value = state.asText();
        if ("CANCELLED".equals(value)) {
            return OrderStatusVerdict.CANCELLED;
        }
        if ("REJECTED".equals(value)) {
            return OrderStatusVerdict.REJECTED;
        }
        if (ACTIVE_STATUSES.contains(value)) {
            return OrderStatusVerdict.ACTIVE;
        }
        return unverifiable(orderId, "status is not one this service knows");
    }

    private static OrderStatusVerdict unverifiable(UUID orderId, String why) {
        log.warn("order-service answer for order {} cannot be trusted: {}", orderId, why);
        return OrderStatusVerdict.UNVERIFIABLE;
    }
}
