package com.sarikaya.e_ticaret.order.entity;

import java.util.Set;

/**
 * Sipariş durumu. Veritabanında ve API'de bu sabit kodlar tutulur; ekrandaki Türkçe yazıyı
 * arayüz seçer. Böylece yazım değişikliği (ör. "Kargoya verildi" → "Kargoda") veriyi bozmaz.
 */
public enum OrderStatus {
    PAYMENT_PENDING,
    PAYMENT_REVIEW,
    PREPARING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    PAYMENT_EXPIRED;

    /** Admin'in elle seçebileceği durumlar. */
    public static final Set<OrderStatus> ADMIN_SELECTABLE = Set.of(PREPARING, SHIPPED, DELIVERED, CANCELLED);

    /** Gerçekleşmiş satış sayılan durumlar. */
    public static final Set<OrderStatus> SOLD = Set.of(PREPARING, SHIPPED, DELIVERED);

    /** Bilinmeyen veya boş değer için null döner. */
    public static OrderStatus parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
