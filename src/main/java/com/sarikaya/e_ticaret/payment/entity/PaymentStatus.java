package com.sarikaya.e_ticaret.payment.entity;

import java.util.Set;

/** Backend'deki ödeme kaydının durumu (iyzico-service'teki deneme durumundan ayrıdır). */
public enum PaymentStatus {
    PENDING,
    PAID,
    REVIEW,
    FAILED,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    /** Paranın çekilmiş (ya da çekilmiş olabileceği) durumlar. */
    public static final Set<PaymentStatus> COLLECTED = Set.of(PAID, REVIEW);
}
