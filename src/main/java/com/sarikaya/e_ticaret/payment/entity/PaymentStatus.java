package com.sarikaya.e_ticaret.payment.entity;

import java.util.Set;

public enum PaymentStatus {
    PENDING,
    PAID,
    REVIEW,
    FAILED,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    public static final Set<PaymentStatus> COLLECTED = Set.of(PAID, REVIEW);
}
