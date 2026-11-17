package com.sarikaya.e_ticaret.payment.events;


public class PaymentEventException extends RuntimeException {
    private final boolean retryable;

    private PaymentEventException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public static PaymentEventException retryLater(String message) {
        return new PaymentEventException(message, true);
    }

    public static PaymentEventException deadLetter(String message) {
        return new PaymentEventException(message, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
