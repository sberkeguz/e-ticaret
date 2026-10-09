package com.sarikaya.e_ticaret.controller.user;

import com.sarikaya.e_ticaret.entity.Brand;
import com.sarikaya.e_ticaret.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// @RequiredArgsConstructor: Lombok kütüphanesinin bir özelliğidir.
// Sınıf içindeki "final" olarak tanımlanmış değişkenler (burada brandRepository)
// için arka planda otomatik olarak bir yapıcı metot (constructor) oluşturur.
// Bu sayede Spring Boot, veritabanı sınıfını bu kontrolcüye otomatik olarak enjekte eder (Dependency Injection).
@RequiredArgsConstructor

// @RestController: Bu sınıfın bir REST API kontrolcüsü olduğunu belirtir.
// Gelen istekleri karşılar ve döndürülen yanıtların (HTML yerine) doğrudan JSON formatında gövdeye (body) yazılmasını sağlar.
@RestController

// @RequestMapping: Bu sınıftaki uç noktaların (endpoint) temel URL'sini belirler.
// Frontend tarafında (React) ziyaretçiler (müşteriler) markaları görmek istediğinde bu adrese istek atar.
@RequestMapping("/api/user/brands")
public class UserBrandController {

    // Markalar tablosuna (veritabanına) erişmek için kullandığımız depo (repository) sınıfı.
    private final BrandRepository brandRepository;

    // @GetMapping: Sadece "/api/user/brands" adresine yapılan HTTP GET (veri okuma/listeleme) isteklerini karşılar.
    // Dikkat edersen burada @PostMapping, @DeleteMapping gibi metotlar yok.
    // Çünkü bu sınıf normal "user" (ziyaretçi/müşteri) için yazıldı. Müşteriler sadece markaları "görebilir", ekleme/silme yapamaz.
    @GetMapping
    public ResponseEntity<List<Brand>> getBrands() {

        // brandRepository.findAll(): Veritabanındaki tüm markaları bulur ve bir liste (List<Brand>) olarak getirir.
        // ResponseEntity.ok(): İşlemin başarılı olduğunu belirten HTTP 200 (OK) durum koduyla birlikte bu listeyi JSON olarak dışarı aktarır.
        return ResponseEntity.ok(brandRepository.findAll());
    }
}