package com.sarikaya.e_ticaret.payment.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * IyzicoService'in ödeme olayı (sürüm 1). Kart verisi içermez.
 * Tanımadığımız yeni alanlar görmezden gelinir: yayıncı alan ekleyebilir, silemez.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(String messageId, String type, int version, String occurredAt, String traceId, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            UUID attemptId,
            Long orderId,
            /* SUCCEEDED | FAILED | VERIFYING | REFUNDED */
            String status,
            BigDecimal amount,
            String currency,
            boolean threeDS,
            String iyzicoPaymentId,
            String failureReason) {
    }
}
