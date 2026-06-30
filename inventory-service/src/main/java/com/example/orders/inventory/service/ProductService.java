package com.example.orders.inventory.service;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Чтение каталога для всех пользователей и изменение для администраторов. */
@Service
public class ProductService {

    /** Размер страницы по умолчанию. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Максимальный размер страницы. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final StockReservationRepository reservations;

    ProductService(ProductRepository products, StockReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    /**
     * @param page номер страницы от нуля; отрицательное значение заменяется нулём
     * @param size размер страницы
     * @param term запрос поиска по каталогу
     * @return страница каталога
     */
    @Transactional(readOnly = true)
    public Page<Product> list(int page, int size, String term) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "name"));
        if (term == null || term.isBlank()) {
            return products.findAll(pageable);
        }
        return products.search(term.trim(), pageable);
    }

    /**
     * @param id идентификатор товара
     * @return товар
     * @throws NotFoundException если товар отсутствует
     */
    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return products.findById(id).orElseThrow(() -> NotFoundException.of("Product", id));
    }

    /**
     * @param ids идентификаторы для поиска
     * @return найденные товары
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> skusOf(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Product::getSku));
    }

    /**
     * @param sku уникальный артикул
     * @param name название
     * @param description описание
     * @param price цена
     * @param availableQuantity начальный доступный остаток
     * @return новый товар
     * @throws ConflictException если артикул уже занят
     */
    @Transactional
    public Product create(String sku, String name, String description, BigDecimal price, int availableQuantity) {
        String normalisedSku = sku.trim();
        if (products.existsBySku(normalisedSku)) {
            throw new ConflictException("SKU is already in use: " + normalisedSku);
        }
        Product product = products.saveAndFlush(
                Product.create(normalisedSku, name.trim(), trimToNull(description), price, availableQuantity));
        log.info("Created product {} ({})", product.getId(), product.getSku());
        return product;
    }

    /**
     * Обновляет редактируемые поля. Остатки меняются через отдельные складские операции.
     * @param id идентификатор товара
     * @param name новое название
     * @param description новое описание
     * @param price новая цена
     * @throws NotFoundException если товар отсутствует
     */
    @Transactional
    public Product update(UUID id, String name, String description, BigDecimal price) {
        Product product = get(id);
        product.updateDetails(name.trim(), trimToNull(description), price);
        return products.saveAndFlush(product);
    }

    /**
     * Пополняет доступный остаток или исправляет его количество.
     * @param id идентификатор товара
     * @param delta изменение количества
     * @throws NotFoundException если товар отсутствует
     * @throws ConflictException если обновление конфликтует с состоянием товара
     */
    @Transactional
    public Product adjustStock(UUID id, int delta) {
        Product product = get(id);
        if (!product.adjustAvailable(delta)) {
            throw new ConflictException("Stock of %s cannot be changed by %d: available is %d"
                    .formatted(product.getSku(), delta, product.getAvailableQuantity()));
        }
        log.info("Adjusted stock of {} by {} to {}", product.getSku(), delta, product.getAvailableQuantity());
        return products.saveAndFlush(product);
    }

    /**
     * Удаляет товар и исторические строки его резервов.
     * @param id идентификатор товара
     * @throws NotFoundException если товар отсутствует
     * @throws ConflictException если у товара остаются активные резервы
     */
    @Transactional
    public void delete(UUID id) {
        Product product = get(id);
        if (reservations.existsByProductIdAndStatus(product.getId(), ReservationStatus.RESERVED)) {
            throw new ConflictException(
                    "Product " + product.getSku() + " still has reserved stock and cannot be deleted");
        }
        // Освобождённые и подтверждённые резервы остаются историей удаляемого товара. Удаляем их перед товаром из-за внешнего ключа.
        reservations.deleteByProductId(product.getId());
        products.delete(product);
        log.info("Deleted product {} ({})", product.getId(), product.getSku());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
