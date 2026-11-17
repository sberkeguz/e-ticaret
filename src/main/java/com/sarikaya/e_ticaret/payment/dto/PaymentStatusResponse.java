package com.sarikaya.e_ticaret.payment.dto;

public record PaymentStatusResponse(String result, String message) {
    public static PaymentStatusResponse of(String result) {
        return new PaymentStatusResponse(result, null);
    }
}
