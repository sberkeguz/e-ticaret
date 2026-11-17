package com.sarikaya.e_ticaret.payment.dto;

/**
 * Ödeme başlatmanın cevabı.
 * result: success | review | failed | threeds | processing | form
 * threeDSHtmlContent: result = threeds iken bankanın SMS sayfası.
 * checkoutFormContent: result = form iken iyzico'nun ödeme formunu açan içerik (kartı müşteri orada girer).
 * orderId: result = processing veya form iken; sonuç GET /api/payments/orders/{orderId}/status ile sorulur.
 */
public record CheckoutResponse(String result, String threeDSHtmlContent, Long orderId, String checkoutFormContent) {
    public static CheckoutResponse of(String result) {
        return new CheckoutResponse(result, null, null, null);
    }

    public static CheckoutResponse threeDS(String htmlContent) {
        return new CheckoutResponse("threeds", htmlContent, null, null);
    }

    public static CheckoutResponse processing(Long orderId) {
        return new CheckoutResponse("processing", null, orderId, null);
    }

    public static CheckoutResponse form(Long orderId, String checkoutFormContent) {
        return new CheckoutResponse("form", null, orderId, checkoutFormContent);
    }
}
