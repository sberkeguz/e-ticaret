package com.sarikaya.e_ticaret.payment.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * IyzicoService'in ödeme olayı (sürüm 1). Kart verisi içermez.
 * Tanımadığımız yeni alanlar görmezden gelinir: yayıncı alan ekleyebilir, silemez.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(String messageId, String type, Integer version, String occurredAt, String traceId, Data data,
                           /* Artan sıra numarası; eski olaylarda yok (null). */ Long sequence) {

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
