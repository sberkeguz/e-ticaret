package com.sarikaya.e_ticaret.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

// @Documented: Bu anotasyonun, projeden oluşturulacak JavaDoc gibi resmi
// kod dokümantasyonlarında görünmesini sağlar
@Documented

// @Retention(RetentionPolicy.RUNTIME) => anotasyonun uygulama çalışırken bellekte tutulmasını sağlar
@Retention(RetentionPolicy.RUNTIME)

@Target({ElementType.FIELD, ElementType.PARAMETER})

// @Constraint => validation işleminin StrongPasswordValidator.class isimli sınıfta yapılacak
@Constraint(validatedBy = StrongPasswordValidator.class)

// public @interface StrongPassword => validation anotasyonumuzu tanımlıyoruz.
public @interface StrongPassword {

    String message() default "Şifre en az 5 karakter olmalıdır.";

    // groups => Jakarta Validation altyapısının zorunlu kıldığı bir alandır
    // Farklı doğrulama kurallarını gruplamak için kullanılır (örneğin sadece Admin kaydında çalışsın gibi).
    // Boş bırakılırsa varsayılan gruba dahil olur.
    Class<?>[] groups() default  {};

    // payload => Jakarta Validationın zorunlu kıldığı bir alandır
    // Doğrulama hatası fırlatıldığında hatanın ciddiyet derecesi
    // ekstra verileri taşımak için kullanılır.
    Class<? extends Payload>[] payload() default{};

    int min() default 5;
}