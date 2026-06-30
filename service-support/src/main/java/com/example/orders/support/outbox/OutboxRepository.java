package com.example.orders.support.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Чтение и очистка записей для фоновой публикации outbox. */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Захватывает очередную пачку неопубликованных событий.
     * @param limit максимальное число строк
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextUnpublished(@Param("limit") int limit);

    /**
     * @param cutoff удалить записи, опубликованные до этого момента
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * @param aggregateId идентификатор агрегата, например заказа
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /** @return неопубликованные строки, начиная с самых старых */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc();
}
