package com.sarikaya.e_ticaret.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** RabbitMQ'ya iletilmeyi bekleyen mesaj (transactional outbox). */
@Entity
@Table(name = "outbox_messages")
@Getter @Setter @NoArgsConstructor
public class OutboxMessage {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String exchange;

    @Column(nullable = false, length = 100)
    private String routingKey;

    @Column(nullable = false, length = 100)
    private String type;

    /** JSON; iletildikten sonra hassas alanları (kart) silinir. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500)
    private String lastError;
}
