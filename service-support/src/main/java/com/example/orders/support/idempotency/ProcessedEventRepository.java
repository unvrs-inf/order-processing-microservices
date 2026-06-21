package com.example.orders.support.idempotency;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Журнал дедупликации. Позволяет проверить, записать и удалить отметки. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /**
     * @param cutoff удалить отметки старше этого времени
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
