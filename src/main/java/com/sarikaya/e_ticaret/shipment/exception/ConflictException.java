package com.sarikaya.e_ticaret.shipment.exception;

import com.sarikaya.e_ticaret.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409: istek, kaydın şu anki durumuyla çelişiyor (ör. SHIPMENT_EXISTS, SHIPMENT_NOT_ALLOWED,
 * SHIPMENT_NOT_RETRYABLE, ORDER_HAS_SHIPMENT). message sabit Türkçe metindir; iç ayrıntı ya da kişisel
 * veri içermez.
 */
public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
