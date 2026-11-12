package com.sarikaya.e_ticaret.exception;

import org.springframework.http.HttpStatus;

/** Ödeme servisine ulaşılamadı veya beklenmedik bir hata döndü. */
public class PaymentServiceUnavailableException extends ApiException {
    public PaymentServiceUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE",
                "Ödeme servisine şu an ulaşılamıyor. Lütfen biraz sonra tekrar deneyin.");
    }
}
