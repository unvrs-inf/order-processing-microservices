package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.service.ReservationResult;
import com.example.orders.inventory.service.ReservationTransition;
import com.example.orders.support.error.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Атомарность, идемпотентность и защита остатков при складских операциях. */
class StockReservationServiceTest extends InventoryIntegrationTest {

    // ------------------------------------------------------------------ резервирование

    @Test
    void reserveMovesStockFromAvailableToReserved() {
        Product first = givenProduct("SKU-RES-1", 10);
        Product second = givenProduct("SKU-RES-2", 4);
        UUID orderId = UUID.randomUUID();

        ReservationResult result = reservationService.reserve(orderId, List.of(item(first, 3), item(second, 4)));

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.RESERVED);
        assertThat(result.shortages()).isEmpty();
        assertThat(result.isHoldingStock()).isTrue();

        assertThat(reload(first).getAvailableQuantity()).isEqualTo(7);
        assertThat(reload(first).getReservedQuantity()).isEqualTo(3);
        assertThat(reload(second).getAvailableQuantity()).isZero();
        assertThat(reload(second).getReservedQuantity()).isEqualTo(4);

        List<StockReservation> rows = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> row.getStatus() == ReservationStatus.RESERVED);
    }

    @Test
    void reserveIsAllOrNothingWhenOneLineHasTooLittleStock() {
        Product plenty = givenProduct("SKU-PLENTY", 50);
        Product scarce = givenProduct("SKU-SCARCE", 2);
        UUID orderId = UUID.randomUUID();

        ReservationResult result = reservationService.reserve(orderId, List.of(item(plenty, 5), item(scarce, 3)));

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.REJECTED);
        assertThat(result.isHoldingStock()).isFalse();
        assertThat(result.shortages()).singleElement().satisfies(shortage -> {
            assertThat(shortage.productId()).isEqualTo(scarce.getId());
            assertThat(shortage.sku()).isEqualTo("SKU-SCARCE");
            assertThat(shortage.reason()).isEqualTo(ReservationResult.Reason.INSUFFICIENT_STOCK);
            assertThat(shortage.requested()).isEqualTo(3);
            assertThat(shortage.available()).isEqualTo(2);
        });

        // При отказе строка, для которой товара хватало, тоже остаётся без изменений.
        assertThat(reload(plenty).getAvailableQuantity()).isEqualTo(50);
        assertThat(reload(plenty).getReservedQuantity()).isZero();
        assertThat(reload(scarce).getAvailableQuantity()).isEqualTo(2);
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
    }

    @Test
    void reserveReportsUnknownProductsWithoutTouchingTheOthers() {
        Product known = givenProduct("SKU-KNOWN", 9);
        UUID missing = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        ReservationResult result = reservationService.reserve(orderId, List.of(
                item(known, 1),
                new OrderItem(missing, "SKU-GONE", 1, known.getPrice())));

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.REJECTED);
        assertThat(result.shortages()).singleElement().satisfies(shortage -> {
            assertThat(shortage.productId()).isEqualTo(missing);
            assertThat(shortage.sku()).isNull();
            assertThat(shortage.reason()).isEqualTo(ReservationResult.Reason.PRODUCT_NOT_FOUND);
        });
        assertThat(reload(known).getAvailableQuantity()).isEqualTo(9);
    }

    @Test
    void reserveIsIdempotentForTheSameOrder() {
        Product product = givenProduct("SKU-IDEMPOTENT", 10);
        UUID orderId = UUID.randomUUID();
        List<OrderItem> items = List.of(item(product, 4));

        assertThat(reservationService.reserve(orderId, items).outcome())
                .isEqualTo(ReservationResult.Outcome.RESERVED);
        ReservationResult second = reservationService.reserve(orderId, items);

        assertThat(second.outcome()).isEqualTo(ReservationResult.Outcome.ALREADY_RESERVED);
        assertThat(second.isHoldingStock()).isTrue();
        assertThat(reload(product).getAvailableQuantity()).isEqualTo(6);
        assertThat(reload(product).getReservedQuantity()).isEqualTo(4);
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
    }

    @Test
    void twoLinesForTheSameProductAreSummedIntoOneReservation() {
        Product product = givenProduct("SKU-MERGED", 10);
        UUID orderId = UUID.randomUUID();

        ReservationResult result = reservationService.reserve(
                orderId, List.of(item(product, 2), item(product, 3)));

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.RESERVED);
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getQuantity()).isEqualTo(5));
        assertThat(reload(product).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void reserveRejectsAnUnusableRequest() {
        Product product = givenProduct("SKU-BAD-REQUEST", 5);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserve(orderId, List.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reservationService.reserve(orderId, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reservationService.reserve(orderId, List.of(item(product, 0))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reservationService.reserve(null, List.of(item(product, 1))))
                .isInstanceOf(BadRequestException.class);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ освобождение

    @Test
    void releasePutsTheStockBackOnTheShelf() {
        Product product = givenProduct("SKU-RELEASE", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 6)));

        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.APPLIED);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(10);
        assertThat(reload(product).getReservedQuantity()).isZero();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    void releaseIsIdempotent() {
        Product product = givenProduct("SKU-RELEASE-TWICE", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 6)));

        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.APPLIED);
        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.ALREADY_APPLIED);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(10);
        assertThat(reload(product).getReservedQuantity()).isZero();
    }

    @Test
    void releasingAnOrderThatNeverReservedIsReportedAsUnknown() {
        assertThat(reservationService.release(UUID.randomUUID())).isEqualTo(ReservationTransition.UNKNOWN_ORDER);
    }

    // ------------------------------------------------------------------ подтверждение

    @Test
    void commitTurnsTheHoldIntoASale() {
        Product product = givenProduct("SKU-COMMIT", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 4)));

        assertThat(reservationService.commit(orderId)).isEqualTo(ReservationTransition.APPLIED);

        Product reloaded = reload(product);
        assertThat(reloaded.getAvailableQuantity()).isEqualTo(6);
        assertThat(reloaded.getReservedQuantity()).isZero();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
    }

    @Test
    void commitIsIdempotent() {
        Product product = givenProduct("SKU-COMMIT-TWICE", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 4)));

        assertThat(reservationService.commit(orderId)).isEqualTo(ReservationTransition.APPLIED);
        assertThat(reservationService.commit(orderId)).isEqualTo(ReservationTransition.ALREADY_APPLIED);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(6);
        assertThat(reload(product).getReservedQuantity()).isZero();
    }

    @Test
    void committingAnOrderThatNeverReservedIsReportedAsUnknown() {
        assertThat(reservationService.commit(UUID.randomUUID())).isEqualTo(ReservationTransition.UNKNOWN_ORDER);
    }

    @Test
    void aReleaseAfterACommitReturnsTheSoldUnitsToStock() {
        Product product = givenProduct("SKU-SOLD", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 4)));
        reservationService.commit(orderId);
        assertThat(reload(product).getAvailableQuantity()).isEqualTo(6);

        // После возврата оплаты товар снова доступен. Подтверждение уже списало его из reserved, поэтому меняется только available.
        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.APPLIED);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(10);
        assertThat(reload(product).getReservedQuantity()).isZero();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    void aSecondReleaseAfterACommitDoesNotReturnTheUnitsTwice() {
        Product product = givenProduct("SKU-SOLD-TWICE", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 4)));
        reservationService.commit(orderId);

        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.APPLIED);
        // Повторный ReleaseStock не меняет остатки.
        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.ALREADY_APPLIED);

        assertThat(reload(product).getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    void committingAnAlreadyReleasedOrderDoesNotSellItAfterAll() {
        Product product = givenProduct("SKU-RELEASED-THEN-COMMIT", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 3)));
        reservationService.release(orderId);

        // Поздняя команда CommitStock после отмены не подтверждает продажу.
        assertThat(reservationService.commit(orderId)).isEqualTo(ReservationTransition.ALREADY_APPLIED);
        assertThat(reload(product).getAvailableQuantity()).isEqualTo(10);
        assertThat(reload(product).getReservedQuantity()).isZero();
    }

    @Test
    void reservationsOfAnOrderAreReadableAfterEveryTransition() {
        Product product = givenProduct("SKU-HISTORY", 10);
        UUID orderId = UUID.randomUUID();

        reservationService.reserve(orderId, List.of(item(product, 1)));
        assertThat(reservationService.reservationsOf(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED));

        reservationService.commit(orderId);
        assertThat(reservationService.reservationsOf(orderId))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
    }

    private Product reload(Product product) {
        return products.findById(product.getId()).orElseThrow();
    }

    private static OrderItem item(Product product, int quantity) {
        return new OrderItem(product.getId(), product.getSku(), quantity, product.getPrice());
    }
}
