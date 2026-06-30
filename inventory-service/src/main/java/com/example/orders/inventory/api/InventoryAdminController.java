package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.service.ProductService;
import com.example.orders.inventory.service.ReservationReconciliation;
import com.example.orders.inventory.service.StockReservationService;
import com.example.orders.support.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Администрирование склада. Все маршруты {@code /api/inventory/**} требуют роль ADMIN.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryAdminController {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdminController.class);

    private final ProductService products;
    private final StockReservationService reservations;
    private final ReservationReconciliation reconciliation;

    InventoryAdminController(
            ProductService products,
            StockReservationService reservations,
            ReservationReconciliation reconciliation) {
        this.products = products;
        this.reservations = reservations;
        this.reconciliation = reconciliation;
    }

    /**
     * @param request данные нового товара
     * @return созданный товар
     */
    @PostMapping("/products")
    ResponseEntity<ProductAdminResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product product = products.create(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.availableQuantity());
        log.info("Admin {} created product {}", CurrentUser.id().orElse(null), product.getSku());
        return ResponseEntity
                .created(URI.create("/api/products/" + product.getId()))
                .body(ProductAdminResponse.of(product));
    }

    /**
     * @param id идентификатор товара
     * @param request изменяемые поля
     * @return обновлённый товар
     */
    @PutMapping("/products/{id}")
    ProductAdminResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return ProductAdminResponse.of(
                products.update(id, request.name(), request.description(), request.price()));
    }

    /**
     * Пополняет доступные остатки или корректирует их.
     * @param id идентификатор товара
     * @param request величина изменения
     * @return товар после изменения
     */
    @PatchMapping("/products/{id}/stock")
    ProductAdminResponse adjustStock(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request) {
        Product product = products.adjustStock(id, request.delta());
        log.info("Admin {} adjusted stock of {} by {}",
                CurrentUser.id().orElse(null), product.getSku(), request.delta());
        return ProductAdminResponse.of(product);
    }

    /**
     * @param id идентификатор удаляемого товара
     */
    @DeleteMapping("/products/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        products.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param orderId заказ для чтения
     * @return его резервы
     */
    @GetMapping("/reservations")
    List<ReservationResponse> reservationsOfOrder(@RequestParam UUID orderId) {
        List<StockReservation> found = reservations.reservationsOf(orderId);
        Map<UUID, String> skus = products.skusOf(
                found.stream().map(StockReservation::getProductId).distinct().toList());
        return found.stream()
                .map(reservation -> ReservationResponse.of(reservation, skus.get(reservation.getProductId())))
                .toList();
    }

    /**
     * Восстанавливает зависшие резервы закрытых заказов. Статус проверяется в order-service с токеном администратора.
     * @param request идентификаторы заказов и причина сверки
     * @param jwt токен администратора
     * @return результат по каждому проверенному заказу
     */
    @PostMapping("/reservations/reconcile")
    ReconciliationResponse reconcileReservations(
            @Valid @RequestBody ReconciliationRequest request, @AuthenticationPrincipal Jwt jwt) {
        log.info("Admin {} runs a reservation reconciliation for {} order(s): {}",
                CurrentUser.id().orElse(null), request.distinctOrderIds().size(), request.reason());
        return new ReconciliationResponse(
                reconciliation.recover(
                                request.distinctOrderIds(), request.reason(), jwt == null ? null : jwt.getTokenValue())
                        .stream()
                        .map(line -> new ReconciliationResponse.Entry(line.orderId(), line.outcome()))
                        .toList());
    }
}
