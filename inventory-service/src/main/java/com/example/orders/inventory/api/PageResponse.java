package com.example.orders.inventory.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Общий формат ответа для постраничных запросов.
 * @param content элементы текущей страницы
 * @param page номер страницы от нуля
 * @param size размер страницы
 * @param totalElements общее число элементов
 * @param totalPages общее число страниц
 * @param last признак последней страницы
 * @param <T> тип элемента
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    /**
     * @param page страница из репозитория
     * @param mapper функция преобразования элемента
     * @param <E> тип исходного элемента
     * @param <T> тип элемента
     */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
