package com.sarikaya.e_ticaret.shipment.dto;

import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;

import java.time.Instant;

/**
 * Müşteriye gösterilen kargo bilgisi (GET /api/orders, sözleşme 1.2). Yönetici alanları (requestNo,
 * failureReason, canRetry) ve alıcı bilgisi bilerek yoktur. Yalnız CREATED / IN_TRANSIT / DELIVERED /
 * DELIVERY_FAILED ve takip numarası doluyken üretilir; aksi hâlde siparişte shipment null'dır.
 */
public record CustomerShipment(ShipmentStatus status, String carrierName, String trackingNumber,
                               String trackingUrl, Instant statusAt) {
}
