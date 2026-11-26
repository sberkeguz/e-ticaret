package com.sarikaya.e_ticaret.shipment.dto;

import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;

import java.time.Instant;

/**
 * Yöneticinin gördüğü gönderi (sözleşme 1.1). trackingUrl yalnız sunucudaki https şablonundan üretilir;
 * failureReason yalnız FAILED'da dolu ve sabit Türkçe metindir (firmanın ham metni dönmez);
 * canRetry yalnız FAILED'da true'dur. requestNo "Tekrar dene"de geri gönderilir.
 */
public record AdminShipment(ShipmentStatus status, String carrierName, String trackingNumber, String trackingUrl,
                            Instant statusAt, int requestNo, String failureReason, boolean canRetry) {
}
