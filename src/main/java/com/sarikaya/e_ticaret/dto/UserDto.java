package com.sarikaya.e_ticaret.dto;

import com.sarikaya.e_ticaret.security.entity.User;

import java.time.LocalDateTime;

// DTO (Data Transfer Object): Veritabanındaki orijinal 'User' sınıfında (entity) kullanıcının
// şifresi (password) gibi dışarı sızmaması gereken çok hassas bilgiler bulunur.
// Bu bilgileri yanlışlıkla frontend'e (React, Postman vb.) göndermemek için,
// sadece dışarıya açılması güvenli olan verileri barındıran bu DTO yapısını kullanırız.
public record UserDto (
        Long id,                     // Kullanıcının veritabanındaki kayıt numarası (ID)
        String username,             // Sisteme giriş yaptığı kullanıcı adı
        String role,                 // Kullanıcının yetkisi (Örneğin: "ADMIN" veya "USER")
        boolean active,              // Hesabın aktif (kullanılabilir) mi yoksa pasif (engellenmiş) mi olduğu
        LocalDateTime lastLoginAt    // Kullanıcının sisteme en son giriş yaptığı tarih ve saat
){

    // from() metodu: Veritabanından çekilen orijinal 'User' nesnesini (user) parametre olarak alır.
    // O nesnenin içinden şifre gibi tehlikeli bilgileri ayıklar (almaz) ve sadece bizim
    // belirlediğimiz yukarıdaki 5 güvenli alanı alarak yeni bir UserDto nesnesi üretir.
    public static UserDto from (User user){
        return new UserDto(
                user.getId(),
                user.getUsername(),

                // user.getRole() metodu arka planda bir Enum (Role.ADMIN vb.) döndürür.
                // .name() ekleyerek bu Enum değerini DTO'nun beklediği düz String (metin) formatına ("ADMIN") çeviriyoruz.
                user.getRole().name(),

                user.isActive(),
                user.getLastLoginAt()
        );
    }
}