package com.sarikaya.e_ticaret.exceptions;

import org.springframework.http.HttpStatus;

// ResourceNotFoundException: Veritabanında veya sistemde aranılan bir kayıt (kaynak)
// bulunamadığında fırlatılan özel hata sınıfıdır. (Örneğin: "5 ID'li marka bulunamadı").
public class ResourceNotFoundException extends ApiException {

    // Yapıcı metot (Constructor)
    public ResourceNotFoundException(String message) {

        // super() ile ata sınıfımız olan ApiException'a gerekli bilgileri gönderiyoruz:
        // 1. HttpStatus.NOT_FOUND: İstek yapılan kaynağın olmadığını belirten meşhur 404 (Not Found) durum kodunu ayarlar.
        // 2. "RESOURCE_NOT_FOUND": Frontend (React) tarafının bu hatayı yakalayıp özel bir ekran
        //    (örn: boş liste veya 404 sayfası) göstermesini kolaylaştıran özel string hata kodu.
        // 3. message: Hatayı tetikleyen yerden gelen dinamik ve açıklayıcı mesaj.
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}