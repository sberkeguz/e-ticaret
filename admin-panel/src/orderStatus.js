// API sipariş ve ödeme durumlarını sabit kodlarla gönderir; ekranda görünen Türkçe yazı burada seçilir.
export const ORDER_STATUS_LABELS = {
    PAYMENT_PENDING: "Ödeme bekliyor",
    PAYMENT_REVIEW: "Ödeme inceleniyor",
    PREPARING: "Hazırlanıyor",
    SHIPPED: "Kargoya verildi",
    DELIVERED: "Teslim edildi",
    CANCELLED: "İptal edildi",
    PAYMENT_EXPIRED: "Ödeme süresi doldu",
};

export const PAYMENT_STATUS_LABELS = {
    PAID: "Ödendi",
    REVIEW: "Ödeme inceleniyor",
    PENDING: "Ödeme bekliyor",
    FAILED: "Ödeme başarısız",
    CANCELLED: "İptal edildi",
    EXPIRED: "Ödeme süresi doldu",
    REFUNDED: "İade edildi",
};

export const ORDER_STEPS = ["PREPARING", "SHIPPED", "DELIVERED"];
export const CANCELLED = "CANCELLED";
export const PAYMENT_PENDING = "PAYMENT_PENDING";

/** Sipariş akışı dışında kalan (adım çubuğu yerine etiketle gösterilen) durumlar. */
export const OFF_TRACK = new Set([CANCELLED, PAYMENT_PENDING, "PAYMENT_REVIEW", "PAYMENT_EXPIRED"]);

export const orderStatusLabel = (status) => ORDER_STATUS_LABELS[status] ?? status;
export const paymentStatusLabel = (status) => (status ? PAYMENT_STATUS_LABELS[status] ?? status : "Ödeme kaydı yok");
