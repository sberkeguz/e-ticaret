package com.sarikaya.e_ticaret.payment.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {
    /**
     * Gönderilmemiş mesajlar, eskiden yeniye. Kilitli okunur ve başka kopyanın kilitlediği satırlar
     * atlanır (SKIP LOCKED): backend birden fazla kopya çalışsa da aynı mesajı ikisi göndermez.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select m from OutboxMessage m where m.publishedAt is null order by m.createdAt")
    List<OutboxMessage> lockUnpublished(Pageable page);

    @Modifying
    @Query("delete from OutboxMessage m where m.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
