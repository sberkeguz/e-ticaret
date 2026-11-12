package com.sarikaya.e_ticaret.payment.dto;

/**
 * "Ödemem ne oldu?" sorusunun cevabı.
 * result: processing | success | review | declined | failed | pending
 * message: declined ise kullanıcıya gösterilecek sebep (ör. "Kart limiti yetersiz").
 */
public record PaymentStatusResponse(String result, String message) {
    public static PaymentStatusResponse of(String result) {
        return new PaymentStatusResponse(result, null);
    }
}
