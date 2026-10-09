package com.sarikaya.e_ticaret.exceptions;

import java.time.LocalDateTime;

// ApiError: Uygulamada bir hata (Exception) meydana geldiğinde, frontend'e (veya Postman'e)
// karmaşık ve okunması zor Java hata loglarını göndermek yerine, bu derli toplu yapıyı (JSON) göndeririz.
// 'record' kullanıldığı için arka planda tüm değişkenler private final olur, getter metotları ve yapıcı metot (constructor) otomatik üretilir.
public record ApiError(
        int status,               // HTTP durum kodu (Örn: 404, 400, 403, 500)
        String code,              // Frontend'in hatanın tipini anlayıp ona göre işlem yapabilmesi için özel hata kodu (Örn: "USER_NOT_FOUND")
        String message,           // Son kullanıcıya gösterilecek okunabilir hata mesajı (Örn: "Kullanıcı bulunamadı")
        LocalDateTime timestamp   // Hatanın meydana geldiği tam tarih ve saat bilgisi
) {}