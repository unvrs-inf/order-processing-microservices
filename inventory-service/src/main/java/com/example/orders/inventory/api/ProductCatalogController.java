package com.example.orders.inventory.api;

import com.example.orders.inventory.service.ProductService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичный каталог. Чтение списка и карточки доступно без токена.
 */
@RestController
@RequestMapping("/api/products")
class ProductCatalogController {

    private final ProductService products;

    ProductCatalogController(ProductService products) {
        this.products = products;
    }

    /**
     * @param page номер страницы от нуля
     * @param size размер страницы
     * @param q текст для поиска
     * @return страница каталога
     */
    @GetMapping
    PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ProductService.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {
        return PageResponse.of(products.list(page, size, q), ProductResponse::of);
    }

    /**
     * @param id идентификатор товара
     * @return карточка товара
     */
    @GetMapping("/{id}")
    ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.of(products.get(id));
    }
}
