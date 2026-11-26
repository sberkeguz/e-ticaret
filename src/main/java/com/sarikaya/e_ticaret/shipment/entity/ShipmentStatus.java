package com.sarikaya.e_ticaret.shipment.entity;

/**
 * Backend'in gördüğü gönderi durumu. cargo-service'teki ara durum SENDING burada REQUESTED olarak kalır
 * (olay üretmez); cargo-service'in REJECTED'ı (ödeme onaylı görülmedi) burada FAILED olur, ayrım
 * failure_code'dadır.
 */
public enum ShipmentStatus {
    /** Kargo komutu yazıldı; cargo-service'ten henüz sonuç gelmedi. */
    REQUESTED,
    /** Kargo firmasında gönderi açıldı, takip numarası var. */
    CREATED,
    IN_TRANSIT,
    DELIVERED,
    /** Teslim denemesi başarısız; kargo firması tekrar dener. */
    DELIVERY_FAILED,
    /** Gönderi oluşturulamadı (koli hiç çıkmadı); "Tekrar dene" ile yeniden istenebilir. */
    FAILED
}
