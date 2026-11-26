package com.sarikaya.e_ticaret.shipment.messaging;

/** Gönderi olayı işlenemedi: retryable ise tekrar denenir, değilse DLQ'ya gider. Mesaj kişisel veri içermez. */
public class ShipmentEventException extends RuntimeException {
    private final boolean retryable;

    private ShipmentEventException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public static ShipmentEventException deadLetter(String message) {
        return new ShipmentEventException(message, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
