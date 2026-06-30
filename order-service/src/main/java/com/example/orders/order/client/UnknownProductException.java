package com.example.orders.order.client;

import com.example.orders.support.error.BadRequestException;
import java.util.UUID;

/**
 * Каталог вернул {@code 404} для запрошенного товара. Запрос на оформление не может быть выполнен.
 */
public class UnknownProductException extends BadRequestException {

    private final UUID productId;

    /**
     * @param productId неизвестный идентификатор товара
     */
    public UnknownProductException(UUID productId) {
        super("Unknown product: " + productId);
        this.productId = productId;
    }

    /** @return идентификатор, который не найден */
    public UUID getProductId() {
        return productId;
    }
}
