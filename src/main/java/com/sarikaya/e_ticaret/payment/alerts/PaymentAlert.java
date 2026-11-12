package com.sarikaya.e_ticaret.payment.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Admin'e düşen ödeme uyarısı. Tablo Flyway ile kurulur (V13). */
@Entity
@Table(name = "payment_alerts")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAlert {
    public enum Kind {
        /** Sipariş zaten ödenmişken aynı siparişe ikinci ödeme alındı. */
        SECOND_PAYMENT,
        /** Süresi dolmuş veya iptal edilmiş siparişe ödeme geldi. */
        LATE_PAYMENT,
        /** Ödemenin ait olduğu sipariş hiç yok (sipariş işlemi geri alınmış). */
        ORPHAN_PAYMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind kind;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, unique = true)
    private UUID attemptId;

    @Column(length = 50)
    private String iyzicoPaymentId;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** true: otomatik iade yapıldı. false: iade yapılamadı/gerekmedi, admin bakmalı. */
    @Column(nullable = false)
    private boolean refunded;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant resolvedAt;
}
