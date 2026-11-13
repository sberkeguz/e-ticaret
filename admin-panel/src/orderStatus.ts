// API sipariş ve ödeme durumlarını sabit kodlarla gönderir; ekranda görünen Türkçe yazı burada seçilir.
import type { OrderStatus, PaymentStatus } from "./types/api";

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
    PAYMENT_PENDING: "Ödeme bekliyor",
    PAYMENT_REVIEW: "Ödeme inceleniyor",
    PREPARING: "Hazırlanıyor",
    SHIPPED: "Kargoya verildi",
    DELIVERED: "Teslim edildi",
    CANCELLED: "İptal edildi",
    PAYMENT_EXPIRED: "Ödeme süresi doldu",
};

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
    PAID: "Ödendi",
    REVIEW: "Ödeme inceleniyor",
    PENDING: "Ödeme bekliyor",
    FAILED: "Ödeme başarısız",
    CANCELLED: "İptal edildi",
    EXPIRED: "Ödeme süresi doldu",
    REFUNDED: "İade edildi",
};

export const ORDER_STEPS: OrderStatus[] = ["PREPARING", "SHIPPED", "DELIVERED"];
export const CANCELLED: OrderStatus = "CANCELLED";
export const PAYMENT_PENDING: OrderStatus = "PAYMENT_PENDING";

/** Sipariş akışı dışında kalan (adım çubuğu yerine etiketle gösterilen) durumlar. */
export const OFF_TRACK = new Set<OrderStatus>([CANCELLED, PAYMENT_PENDING, "PAYMENT_REVIEW", "PAYMENT_EXPIRED"]);

export const orderStatusLabel = (status: OrderStatus | string): string => ORDER_STATUS_LABELS[status as OrderStatus] ?? status;
export const paymentStatusLabel = (status: PaymentStatus | null): string => (status ? PAYMENT_STATUS_LABELS[status] ?? status : "Ödeme kaydı yok");
