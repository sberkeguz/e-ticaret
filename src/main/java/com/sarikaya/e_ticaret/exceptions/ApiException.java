package com.sarikaya.e_ticaret.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// @Getter: Lombok kütüphanesi sayesinde, bu sınıftaki 'status' ve 'code' değişkenleri için
// otomatik olarak getStatus() ve getCode() metotlarını oluşturur.
@Getter

// abstract: Bu sınıf "Soyut" bir sınıftır. Yani kodun hiçbir yerinde doğrudan "new ApiException()" diyerek
// bu sınıftan nesne üretilemez. Bu sınıf, projedeki diğer özel hataların (Örn: ResourceNotFoundException)
// "Atası" (parent) olmak için tasarlanmıştır. Diğer hatalar bu sınıftan miras (extends) alarak çoğalır.
// RuntimeException: Java'da "Unchecked (Kontrol Edilmeyen)" hata türüdür. Bu sayede her metot imzasında
// "throws Exception" yazma zorunluluğundan kurtuluruz ve kodu temiz tutarız.
public abstract class ApiException extends RuntimeException {

    // Her hatanın bir HTTP durum kodu (404, 400 vb.) ve özel bir metin kodu ("NOT_FOUND" vb.) olmalıdır.
    // 'final' oldukları için bu değerler nesne oluşturulurken verilir ve sonradan değiştirilemez.
    private final HttpStatus status;
    private final String code;

    // Sınıfın yapıcı metodu (Constructor).
    // Bu sınıftan miras alan alt hatalar, bu constructor'ı çağırarak hata bilgilerini doldurmak zorundadır.
    protected ApiException(HttpStatus status, String code, String message) {

        // super(message): Hata mesajını, miras aldığımız asıl Java RuntimeException sınıfına gönderir.
        // Böylece Java'nın kendi hata loglama mekanizması da bu mesajı tanımış olur.
        super(message);

        this.status = status;
        this.code = code;
    }
}