package com.sarikaya.e_ticaret.payment.events;

/**
 * Olay şu an işlenemedi. İki türü var:
 * <ul>
 *   <li>retryLater: biraz sonra büyük ihtimalle işlenebilir (ör. sipariş henüz kaydedilmemiş).</li>
 *   <li>deadLetter: beklemek çözmez, bir insanın bakması gerekir (ör. aynı siparişe ikinci ödeme).</li>
 * </ul>
 */
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
