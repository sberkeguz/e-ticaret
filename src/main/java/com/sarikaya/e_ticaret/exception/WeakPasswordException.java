<<<<<<<< HEAD:src/main/java/com/sarikaya/e_ticaret/exception/WeakPasswordException.java
package com.sarikaya.e_ticaret.exception;
========
package com.sarikaya.e_ticaret.common.exception;
>>>>>>>> origin/master:src/main/java/com/sarikaya/e_ticaret/common/exception/WeakPasswordException.java

import org.springframework.http.HttpStatus;

// WeakPasswordException: Kullanıcının belirlediği şifre, sistemin güvenlik standartlarını
// (örneğin daha önce yazdığımız @StrongPassword kuralını veya diğer karmaşıklık kurallarını)
// karşılamadığında fırlatılan özel hata sınıfıdır.
public class WeakPasswordException extends ApiException {

    public WeakPasswordException(String message) {
        super(HttpStatus.FORBIDDEN, "WEAK_PASSWORD", message);
    }
}