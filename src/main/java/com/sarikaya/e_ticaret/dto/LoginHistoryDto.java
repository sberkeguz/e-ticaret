package com.sarikaya.e_ticaret.dto;

import com.sarikaya.e_ticaret.security.entity.LoginHistory;

import java.time.LocalDateTime;

// DTO (Data Transfer Object - Veri Transfer Nesnesi): Veritabanı varlıklarını (Entity)
// doğrudan dışarıya (frontend'e) açmak güvenlik açıklarına ve performans sorunlarına yol açar.
// Bu yüzden sadece dışarı gönderilmesi gereken verileri barındıran bu tür DTO sınıfları oluştururuz.

// record: Daha önce bahsettiğimiz gibi, Java'da sadece veri taşımak için kullanılan,
// arka planda getter, yapıcı metot (constructor) ve toString gibi işlemleri kendi halleden kısa yapıdır.
public record LoginHistoryDto (
        Long id,                  // Giriş işleminin veritabanındaki ID numarası
        LocalDateTime loggedInAt, // Sisteme giriş yapılan tam tarih ve saat bilgisi
        String ipAddress,         // Kullanıcının giriş yaparken kullandığı IP adresi
        String userAgent          // Kullanıcının tarayıcı (Chrome, Safari vb.) ve cihaz bilgisi
){

    // from() metodu: Bu bir "Factory (Fabrika)" metodudur. Dönüşüm işlemlerini kolaylaştırır.
    // Veritabanından gelen ağır ve karmaşık 'LoginHistory' entity (varlık) nesnesini (h) parametre olarak alır.
    // O nesnenin içinden sadece yukarıda tanımladığımız 4 temel veriyi çekip çıkararak
    // yepyeni, temiz ve güvenli bir 'LoginHistoryDto' nesnesi oluşturur ve bunu döndürür.
    // AdminUserController sınıfında gördüğümüz ".map(LoginHistoryDto::from)" kısmı işte tam olarak bu metodu tetikler.
    public static LoginHistoryDto from(LoginHistory h){
        return new LoginHistoryDto(
                h.getId(),
                h.getLoggedInAt(),
                h.getIpAddress(),
                h.getUserAgent()
        );
    }
}