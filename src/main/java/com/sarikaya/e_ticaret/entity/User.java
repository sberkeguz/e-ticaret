package com.sarikaya.e_ticaret.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// @Entity: Spring ve Hibernate'e bu Java sınıfının veritabanında bir "Tabloya" karşılık geldiğini söyler.
@Entity
// @Table: Veritabanında oluşacak tablonun adını belirler (bunu yazmazsak varsayılan olarak sınıf adını yani "user"ı kullanırdı).
@Table(name="users")
// @Data: Lombok kütüphanesi komutudur. Getter, Setter, toString ve equals metotlarının hepsini bizim yerimize arka planda yazar.
@Data
// @Builder: Nesne oluştururken "User.builder().username("...").build()" şeklinde çok daha okunaklı ve pratik bir yapı kullanmamızı sağlar.
@Builder
// @NoArgsConstructor: Parametresiz (boş) bir constructor oluşturur. Hibernate'in veritabanından veri çekerken nesne yaratabilmesi için bu zorunludur.
@NoArgsConstructor
// @AllArgsConstructor: Sınıftaki tüm değişkenleri parametre olarak alan dolu bir constructor oluşturur (Builder yapısının arka planda çalışabilmesi için gereklidir).
@AllArgsConstructor
public class User implements UserDetails {

    // @Id: Bu değişkenin tablonun Birincil Anahtarı (Primary Key - Benzersiz Kimlik) olduğunu belirtir.
    @Id
    // @GeneratedValue: ID değerinin veritabanı tarafından otomatik olarak (1, 2, 3...) artırılacağını belirtir (SQL'deki AUTO_INCREMENT).
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @Column: Sütun özelliklerini belirler. unique = true (aynı kullanıcı adından iki tane olamaz), nullable = false (bu alan boş bırakılamaz).
    @Column(unique = true, nullable = false)
    private String username;

    // Şifre alanının veritabanına boş kaydedilmesini engeller.
    @Column(nullable = false)
    private String password;

    // @Enumerated: Normalde Java'daki Enum'lar veritabanına 0, 1, 2 gibi sıra numarasıyla (Ordinal) kaydedilir.
    // EnumType.STRING diyerek, veritabanına sayılar yerine doğrudan "USER" veya "ADMIN" metinlerinin kaydedilmesini garanti ediyoruz.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Hesabın aktif olup olmadığını tutan değişken.
    // @Builder.Default: Builder ile nesne oluşturulurken bu varsayılan değerin (true) korunmasını sağlar.
    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    // @Override: UserDetails arayüzünden (interface) zorunlu olarak gelen kuralları ezdiğimizi ve kendi mantığımıza göre doldurduğumuzu belirtir.
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Kullanıcının Enum rolünü (örneğin USER), Spring Security'nin tanıyabileceği SimpleGrantedAuthority nesnesine dönüştürüp liste olarak veriyoruz.
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    // Hesap süresinin dolup dolmadığını kontrol eder. true = süresiz geçerli.
    @Override
    public boolean isAccountNonExpired(){return true;}

    // Hesabın kilitli olup olmadığını kontrol eder. true = kilitli değil.
    @Override
    public boolean isAccountNonLocked() { return true; }

    // Şifrenin kullanım süresinin dolup dolmadığını kontrol eder. true = şifre geçerli.
    @Override
    public boolean isCredentialsNonExpired(){return true;}

    // Hesabın aktif/kullanılabilir olup olmadığını kontrol eder.
    // Eskiden sabit "true" diyorduk, artık veritabanındaki isActive durumuna bakacak.
    @Override
    public boolean isEnabled() {
        return this.isActive;
    }
}