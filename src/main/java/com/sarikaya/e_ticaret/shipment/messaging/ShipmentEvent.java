package com.sarikaya.e_ticaret.shipment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * cargo-service'in gönderi olayı (v1; cargo-service Messaging/Contracts/ShipmentEvent.cs). JSON camelCase;
 * zamanlar "+00:00" ofsetli, en çok 7 haneli kesirli ISO-8601 metindir. Kişisel veri içermez.
 * Sürüm kuralı gereği bilinmeyen alanlar yok sayılır; eksik zorunlu alan dinleyicide DLQ'ya gönderilir.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentEvent(String messageId, String type, Integer version, OffsetDateTime occurredAt,
                            String traceId, Integer sequence, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Long orderId,
            UUID shipmentId,
            Integer requestNo,
            /* CREATED | IN_TRANSIT | DELIVERED | DELIVERY_FAILED | FAILED | REJECTED */
            String status,
            String carrierCode,
            String carrierName,
            String trackingNumber,
            OffsetDateTime statusAt,
            /* Firmanın/servisin metni: saklanmaz, loglanmaz, kullanıcıya dönmez (S4). */
            String failureReason) {
    }
}
