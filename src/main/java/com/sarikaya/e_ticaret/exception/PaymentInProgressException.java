package com.sarikaya.e_ticaret.exception;

import org.springframework.http.HttpStatus;

public class PaymentInProgressException extends ApiException {
    public PaymentInProgressException(String message) {
        super(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS", message);
    }
}
