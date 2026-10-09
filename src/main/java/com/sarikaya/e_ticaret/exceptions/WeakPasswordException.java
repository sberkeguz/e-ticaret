package com.sarikaya.e_ticaret.exceptions;

import org.springframework.http.HttpStatus;

// WeakPasswordException: Kullanıcının belirlediği şifre, sistemin güvenlik standartlarını
// (örneğin daha önce yazdığımız @StrongPassword kuralını veya diğer karmaşıklık kurallarını)
// karşılamadığında fırlatılan özel hata sınıfıdır.
public class WeakPasswordException extends ApiException {

    public WeakPasswordException(String message) {

        // 1. HttpStatus.FORBIDDEN: HTTP 403 durum kodu. Şifre zayıf olduğu için işlemin reddedildiğini ifade eder.
        // 2. "WEAK_PASSWORD": Frontend tarafında şifre kutucuğunu uyarı rengine (kırmızı/turuncu) boyamak veya
        //    kullanıcıya "Şifreniz çok zayıf, lütfen daha güçlü bir şifre girin" mesajı göstermek için referans alınacak koddur.
        super(HttpStatus.FORBIDDEN, "WEAK_PASSWORD", message);
    }
}