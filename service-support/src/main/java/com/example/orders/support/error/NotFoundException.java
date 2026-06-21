package com.example.orders.support.error;

/** Ресурс отсутствует. API возвращает {@code 404}. */
public class NotFoundException extends RuntimeException {

    /**
     * @param message сообщение для клиента
     */
    public NotFoundException(String message) {
        super(message);
    }

    /**
     * @param resource тип ресурса, например {@code "Product"}
     * @param id идентификатор записи
     */
    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(resource + " " + id + " does not exist");
    }
}
