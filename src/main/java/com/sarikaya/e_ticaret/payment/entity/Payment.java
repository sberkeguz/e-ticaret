package com.sarikaya.e_ticaret.payment.entity;

import com.sarikaya.e_ticaret.order.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    /** iyzico ödeme numarası. */
    @Column(unique = true)
    private String token;

    /** IyzicoService'teki son ödeme denemesinin numarası (iyzico'ya conversationId olarak gider). */
    @Column(unique = true)
    private UUID attemptId;

    /** Bu ödemeye uygulanan son olayın sıra numarası; daha eski olaylar uygulanmaz. */
    private Long lastEventSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
