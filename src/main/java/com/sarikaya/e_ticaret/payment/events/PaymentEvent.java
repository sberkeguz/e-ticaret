package com.sarikaya.e_ticaret.payment.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(String messageId, String type, Integer version, String occurredAt, String traceId, Data data, Long sequence) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            UUID attemptId,
            Long orderId,
            /* SUCCEEDED | FAILED | VERIFYING | REFUNDED */
            String status,
            BigDecimal amount,
            String currency,
            /* Eksik gelirse null; sürüm kuralı gereği eksik alan mesajı reddettirmez. */ Boolean threeDS,
            String iyzicoPaymentId,
            String failureReason) {
    }
}