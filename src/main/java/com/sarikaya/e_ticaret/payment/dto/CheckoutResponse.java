package com.sarikaya.e_ticaret.payment.dto;

/**
 * result: success | review | failed | threeds | processing
 * threeDSHtmlContent: yalnızca result = threeds olduğunda dolu; bankanın doğrulama sayfası.
 * orderId: result = processing olduğunda dolu; sonucu GET /api/payments/orders/{orderId}/status ile sorulur.
 */
public record CheckoutResponse(String result, String threeDSHtmlContent, Long orderId) {
    public static CheckoutResponse of(String result) {
        return new CheckoutResponse(result, null, null);
    }

    public static CheckoutResponse threeDS(String htmlContent) {
        return new CheckoutResponse("threeds", htmlContent, null);
    }

    /** 2D ödeme kabul edildi, sonuç birkaç saniye içinde belli olacak. */
    public static CheckoutResponse processing(Long orderId) {
        return new CheckoutResponse("processing", null, orderId);
    }
}
