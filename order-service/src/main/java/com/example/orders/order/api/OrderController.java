package com.example.orders.order.api;

import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.service.OrderPlacement;
import com.example.orders.order.service.OrderService;
import com.example.orders.support.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API заказов. Для каждого маршрута нужен токен. Доступ зависит от владельца заказа и ролей в токене.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    /** Необязательный заголовок для повторных запросов {@code POST /api/orders}. */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Роль для чтения заказов других пользователей. */
    private static final String ADMIN = "ADMIN";

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Оформляет заказ для текущего пользователя.
     * @param request строки заказа
     * @param idempotencyKey ключ идемпотентности
     * @return созданный или ранее оформленный заказ
     */
    @PostMapping
    ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        OrderPlacement placement = orders.place(CurrentUser.requireId(), request, idempotencyKey);
        URI location = URI.create("/api/orders/" + placement.order().id());
        return placement.created()
                ? ResponseEntity.created(location).body(placement.order())
                : ResponseEntity.ok().location(location).body(placement.order());
    }

    /**
     * @param page номер страницы от нуля
     * @param size размер страницы
     * @return заказы текущего пользователя
     */
    @GetMapping("/me")
    PageResponse<OrderResponse> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OrderService.DEFAULT_PAGE_SIZE) int size) {
        return PageResponse.of(orders.listOwn(CurrentUser.requireId(), page, size));
    }

    /**
     * Список заказов всех пользователей, только для ADMIN.
     * @param status необязательный фильтр статуса
     * @param page номер страницы
     * @param size размер страницы
     */
    @GetMapping
    PageResponse<OrderResponse> all(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OrderService.DEFAULT_PAGE_SIZE) int size) {
        return PageResponse.of(orders.listAll(status, page, size));
    }

    /**
     * @param id идентификатор заказа
     * @return заказ владельца или доступный администратору
     */
    @GetMapping("/{id}")
    OrderResponse one(@PathVariable UUID id) {
        return orders.get(id, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }

    /**
     * События и команды заказа в порядке публикации. Ответ содержит метаданные Kafka и трассировки без тел событий.
     * @param id идентификатор заказа
     */
    @GetMapping("/{id}/events")
    List<OrderEventResponse> events(@PathVariable UUID id) {
        return orders.events(id, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }

    /**
     * Отменяет заказ владельца. Администратор может отменить заказ любого пользователя.
     * @param id идентификатор заказа
     */
    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable UUID id) {
        return orders.cancel(id, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }
}
