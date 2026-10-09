package com.sarikaya.e_ticaret.exceptions;

import org.springframework.http.HttpStatus;

// UsernameAlreadyExistsException: Yeni bir kullanıcı kayıt olurken veya mevcut bir kullanıcı
// kullanıcı adını değiştirirken, seçilen ismin veritabanında zaten başkası tarafından
// kullanılıyor olması durumunda fırlatılır.
public class UsernameAlreadyExistsException extends ApiException {

    public UsernameAlreadyExistsException(String message) {

        // 1. HttpStatus.FORBIDDEN: HTTP 403 (Yasak). Kullanıcının bu isimle işleme devam etmesinin
        //    sistem tarafından reddedildiğini (yasaklandığını) ifade eder. (Not: Bu tür veri çakışmalarında
        //    alternatif olarak 409 CONFLICT veya 400 BAD REQUEST de endüstride sıkça kullanılır).
        // 2. "USERNAME_TAKEN": Frontend'de kayıt formundaki input'un altına kırmızı harflerle
        //    "Bu kullanıcı adı alınmış" uyarısını basmak için yakalanacak özel anahtar (kod).
        super(HttpStatus.FORBIDDEN, "USERNAME_TAKEN", message);
    }
}