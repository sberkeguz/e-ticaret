package com.sarikaya.e_ticaret.annotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// ConstraintValidator<StrongPassword, String> =>
// String tipindeki verileri denetler
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private int min;

    // initialize => validator ilk kez oluşturulduğunda bir kez çalışır
    // @StrongPassword anotasyonunun içine yazılan değeri alır ve
    // yukarıdaki this.min değişkenine atar
    @Override
    public void initialize(StrongPassword annotation){
        this.min = annotation.min();
    }

    // isValid => Doğrulamanın yapıldığı asıl metottur
    // Gelen veri kurala uyuyorsa true uymuyorsa false döndürür
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context){
        if (value == null) {
            return true;
        }

        return !value.isBlank() && value.length() >= min;
    }
}