package com.sarikaya.e_ticaret.exception;

/** IyzicoService "bu ödeme iade edilemez" dedi (zaten iade edilmiş ya da para çekilmemiş). */
public class NotRefundableException extends BadRequestException {
    public NotRefundableException(String message) {
        super(message);
    }
}
