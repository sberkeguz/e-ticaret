package com.sarikaya.e_ticaret.payment.dto;

/**
 * result: success | review | failed | threeds
 * threeDSHtmlContent: yalnızca result = threeds olduğunda dolu; bankanın doğrulama sayfası.
 */
public record CheckoutResponse(String result, String threeDSHtmlContent) {
    public static CheckoutResponse of(String result) {
        return new CheckoutResponse(result, null);
    }

    public static CheckoutResponse threeDS(String htmlContent) {
        return new CheckoutResponse("threeds", htmlContent);
    }
}
