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

    @Column(unique = true)
    private String token;

    @Column(unique = true)
    private UUID attemptId;

    private Long lastEventSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Kuyrukla giden 2D isteğinin son bilinen durumu (olaylardan güncellenir): REQUESTED, SUCCEEDED,
     * FAILED, VERIFYING. Boşsa ödeme kuyruksuz yoldan (3D) yapılıyordur.
     */
    @Column(length = 30)
    private String lastAttemptStatus;

    /** Başarısız denemenin kullanıcıya gösterilecek sebebi (ör. "Kart limiti yetersiz"). */
    @Column(length = 500)
    private String failureReason;

    /** iyzico-service bu andan sonra gelen 2D isteğini iyzico'ya göndermez. */
    private Instant chargeDeadline;
}
