package com.sarikaya.e_ticaret.common.exception;

import org.springframework.http.HttpStatus;

// WeakPasswordException: Kullanıcının belirlediği şifre, sistemin güvenlik standartlarını
// (örneğin daha önce yazdığımız @StrongPassword kuralını veya diğer karmaşıklık kurallarını)
// karşılamadığında fırlatılan özel hata sınıfıdır.
public class WeakPasswordException extends ApiException {

    public WeakPasswordException(String message) {
        super(HttpStatus.FORBIDDEN, "WEAK_PASSWORD", message);
    }
}