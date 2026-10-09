package com.sarikaya.e_ticaret.exceptions;

import org.springframework.http.HttpStatus;

// BadRequestException: İstemciden (Frontend, Mobil, Postman vb.) hatalı, eksik
// veya kurallara uymayan bir veri geldiğinde fırlatılan özel hata sınıfıdır.
// Daha önce yazdığımız 'ApiException' soyut sınıfından miras (extends) alır.
public class BadRequestException extends ApiException {

    // Yapıcı metot (Constructor): Bu hatayı fırlatırken sadece kullanıcıya
    // veya loglara yansıyacak olan özel 'message' (mesaj) bilgisini dışarıdan alırız.
    public BadRequestException(String message) {

        // super(): Miras alınan 'ApiException' sınıfının yapıcı metoduna verileri gönderir.
        // 1. HttpStatus.BAD_REQUEST: Hatanın HTTP durum kodunu sabit olarak 400 (Bad Request) yapar.
        // 2. "BAD_REQUEST": Frontend'in hatayı sınıflandırabilmesi için özel hata kodu.
        // 3. message: Fırlatılan yere özgü (örneğin "Geçersiz şifre formatı") metin mesajı.
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}