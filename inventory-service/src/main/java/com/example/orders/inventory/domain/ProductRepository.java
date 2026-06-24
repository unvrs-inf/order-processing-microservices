package com.example.orders.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Поиск каталога по идентификатору и артикулу, пагинация и текстовый поиск. */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    /**
     * @param term подстрока для поиска по названию и SKU без учёта регистра
     * @param pageable параметры страницы и сортировки
     */
    @Query("""
            select p from Product p
            where lower(p.name) like lower(concat('%', :term, '%'))
               or lower(p.sku) like lower(concat('%', :term, '%'))
            """)
    Page<Product> search(@Param("term") String term, Pageable pageable);

    /**
     * Загружает товары для резервирования и блокирует строки до конца транзакции.
     * @param ids идентификаторы для поиска
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> lockAllByIdIn(@Param("ids") Collection<UUID> ids);
}
