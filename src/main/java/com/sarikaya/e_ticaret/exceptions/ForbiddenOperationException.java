package com.sarikaya.e_ticaret.exceptions;

import org.springframework.http.HttpStatus;

// ForbiddenOperationException: Sisteme giriş yapmış ve yetkisi (örneğin ADMIN) olan bir kullanıcının,
// sistemin güvenliği veya iş kuralları gereği yapmaması gereken bir işlemi denediğinde fırlatılır.
// (Örneğin AdminUserController'da gördüğümüz "Admin kendi hesabını silemez" kuralı gibi).
public class ForbiddenOperationException extends ApiException{

    // Yapıcı metot (Constructor)
    public ForbiddenOperationException(String message){

        // super() ile ata sınıf olan ApiException'a gerekli 3 parametreyi göndeririz:
        // 1. HttpStatus.FORBIDDEN: Hatanın HTTP durum kodunu sabit olarak 403 (Forbidden - Yasak) yapar.
        //    (401 Unauthorized ile karıştırılmamalıdır; 401 kimlik bilinmediğinde, 403 kimlik bilinip yetki/kural reddedildiğinde döner).
        // 2. "FORBIDDEN_OPERATION": Frontend'e gidecek özel hata sabiti.
        // 3. message: Yasaklanma sebebini açıklayan dinamik mesaj (Örn: "Kendi rolünüzü değiştiremezsiniz").
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_OPERATION", message);
    }
}