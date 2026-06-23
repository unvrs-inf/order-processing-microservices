package com.example.orders.order.service;

import com.example.orders.order.api.CreateOrderRequest;
import com.example.orders.order.api.OrderEventResponse;
import com.example.orders.order.api.OrderItemRequest;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.order.client.CatalogProduct;
import com.example.orders.order.client.ProductCatalogClient;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderLine;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.saga.OrderSaga;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import com.example.orders.support.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Создание и чтение заказов.
 * <p>Запрос проверяется и нормализуется, затем сервис получает цены из каталога. {@link OrderTransactions} записывает заказ, строки и событие {@code OrderCreated} в одной транзакции. Сетевой запрос выполняется отдельно от транзакции базы.
 */
@Service
public class OrderService {

    /** Размер страницы по умолчанию. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Максимальный размер страницы. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Предельная длина {@code Idempotency-Key} соответствует размеру столбца. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ProductCatalogClient catalog;
    private final OrderTransactions transactions;
    private final OrderSaga saga;
    private final OutboxRepository outbox;

    OrderService(
            OrderRepository orders,
            ProductCatalogClient catalog,
            OrderTransactions transactions,
            OrderSaga saga,
            OutboxRepository outbox) {
        this.orders = orders;
        this.catalog = catalog;
        this.transactions = transactions;
        this.saga = saga;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ команды

    /**
     * @param userId владелец из токена
     * @param request строки из запроса
     * @param idempotencyKey необязательный заголовок {@code Idempotency-Key}
     * @return заказ и признак его создания этим вызовом
     * @throws BadRequestException если запрос некорректен или товар отсутствует
     * @throws ConflictException если ключ уже связан с другим списком строк
     */
    public OrderPlacement place(UUID userId, CreateOrderRequest request, String idempotencyKey) {
        Map<UUID, Integer> wanted = merge(request.items());
        String key = normaliseKey(idempotencyKey);

        if (key != null) {
            Optional<Order> replayed = orders.findByUserIdAndIdempotencyKey(userId, key);
            if (replayed.isPresent()) {
                return replay(replayed.get(), wanted, key);
            }
        }

        List<PricedLine> priced = price(wanted);
        try {
            Order order = transactions.placeOrder(userId, key, Order.DEFAULT_CURRENCY, priced);
            log.info("Placed order {} for user {} with {} line(s), total {} {}",
                    order.getId(), userId, priced.size(), order.getTotalAmount(), order.getCurrency());
            return new OrderPlacement(OrderResponse.of(order), true);
        }
        catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            // При одновременных запросах с одним ключом уникальное ограничение выбирает один заказ. Второй запрос возвращает тот же заказ.
            Order winner = orders.findByUserIdAndIdempotencyKey(userId, key).orElseThrow(() -> e);
            return replay(winner, wanted, key);
        }
    }

    /**
     * Запрашивает отмену. Saga пишет новый статус и команды компенсации в одной транзакции.
     * @param orderId заказ для отмены
     * @param callerId идентификатор пользователя
     * @param admin наличие роли {@code ADMIN}
     * @return заказ после начала отмены
     * @throws NotFoundException если заказ отсутствует или принадлежит другому пользователю
     * @throws ConflictException если заказ отклонён, отменён или уже отменяется
     */
    public OrderResponse cancel(UUID orderId, UUID callerId, boolean admin) {
        return saga.cancel(orderId, callerId, admin);
    }

    // ------------------------------------------------------------------ запросы

    /**
     * Чужой заказ отвечает {@code 404}, чтобы не раскрывать его существование. Администратору доступны все заказы.
     * @param orderId заказ для чтения
     * @param callerId идентификатор пользователя
     * @param admin наличие роли {@code ADMIN}
     * @return заказ
     * @throws NotFoundException если заказ отсутствует или недоступен
     */
    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId, UUID callerId, boolean admin) {
        return orders.findWithItemsById(orderId)
                .filter(order -> admin || order.getUserId().equals(callerId))
                .map(OrderResponse::of)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    /**
     * События и команды по заказу в порядке записи. Доступ как у {@link #get(UUID, UUID, boolean)}.
     * @param orderId заказ для чтения событий
     * @param callerId идентификатор пользователя
     * @param admin наличие роли {@code ADMIN}
     * @return строки outbox для заказа без тел сообщений
     * @throws NotFoundException если заказ отсутствует или недоступен
     */
    @Transactional(readOnly = true)
    public List<OrderEventResponse> events(UUID orderId, UUID callerId, boolean admin) {
        get(orderId, callerId, admin);
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderEventResponse::of)
                .toList();
    }

    /**
     * @param userId владелец
     * @param page номер страницы от нуля; отрицательные значения заменяются нулём
     * @param size размер страницы от 1 до {@value #MAX_PAGE_SIZE}
     * @return заказы пользователя, начиная с новых
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listOwn(UUID userId, int page, int size) {
        return orders.findByUserId(userId, newestFirst(page, size)).map(OrderResponse::of);
    }

    /**
     * @param status необязательный статус; при {@code null} возвращаются все
     * @param page номер страницы от нуля
     * @param size размер страницы от 1 до {@value #MAX_PAGE_SIZE}
     * @return заказы всех пользователей, начиная с новых
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listAll(OrderStatus status, int page, int size) {
        Pageable pageable = newestFirst(page, size);
        Page<Order> found = status == null
                ? orders.findAll(pageable)
                : orders.findByStatus(status, pageable);
        return found.map(OrderResponse::of);
    }

    // ------------------------------------------------------------------ вспомогательные операции

    /**
     * Объединяет одинаковые товары с сохранением порядка первого появления. В {@code order_items} одна строка на пару заказ–товар.
     */
    private static Map<UUID, Integer> merge(List<OrderItemRequest> items) {
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        for (Map.Entry<UUID, Integer> line : merged.entrySet()) {
            if (line.getValue() > OrderItemRequest.MAX_QUANTITY) {
                throw new BadRequestException(
                        "Quantity for product %s adds up to %d, the maximum is %d"
                                .formatted(line.getKey(), line.getValue(), OrderItemRequest.MAX_QUANTITY));
            }
        }
        if (merged.size() > CreateOrderRequest.MAX_ITEMS) {
            throw new BadRequestException("An order may contain at most %d distinct products"
                    .formatted(CreateOrderRequest.MAX_ITEMS));
        }
        return merged;
    }

    /** Один запрос каталога на товар. Неизвестный товар прерывает оформление с ответом 400. */
    private List<PricedLine> price(Map<UUID, Integer> wanted) {
        List<PricedLine> priced = new ArrayList<>(wanted.size());
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            CatalogProduct product = catalog.fetch(line.getKey());
            priced.add(new PricedLine(
                    line.getKey(), product.sku(), product.name(), product.price(), line.getValue()));
        }
        return priced;
    }

    private static String normaliseKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * Совпадение ключа требует совпадения тела. Иначе сервис сообщает о конфликте.
     */
    private static OrderPlacement replay(Order existing, Map<UUID, Integer> wanted, String key) {
        Map<UUID, Integer> stored = existing.getItems().stream()
                .collect(Collectors.toMap(OrderLine::getProductId, OrderLine::getQuantity));
        if (!stored.equals(wanted)) {
            throw new ConflictException(
                    "Idempotency-Key '%s' was already used for a different order".formatted(key));
        }
        log.info("Idempotency-Key '{}' replayed order {}", key, existing.getId());
        return new OrderPlacement(OrderResponse.of(existing), false);
    }

    private static Pageable newestFirst(int page, int size) {
        return PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                // Сортировка по идентификатору стабилизирует страницы при совпадении времени создания.
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
