package com.sarikaya.e_ticaret.shipment.service;

import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;

/**
 * Gönderi hata kodunun yöneticiye gösterilen sabit Türkçe metni (Güvenlik S4). Firmanın ham hata metni,
 * yığın izi ya da alıcı bilgisi buraya hiç gelmez; bilinmeyen kod da firma hatası metniyle gösterilir.
 */
public final class FailureTexts {
    static final String CARRIER_FAILED = "Kargo firması gönderiyi oluşturamadı.";
    static final String PAYMENT_NOT_CONFIRMED = "Kargo servisi ödemeyi onaylı görmedi; ödemeyi kontrol edip tekrar deneyin.";

    private FailureTexts() {
    }

    public static String forCode(String failureCode) {
        return OrderShipment.PAYMENT_NOT_CONFIRMED.equals(failureCode) ? PAYMENT_NOT_CONFIRMED : CARRIER_FAILED;
    }
}
