package com.sarikaya.e_ticaret.annotation;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// RUNTIME: Annotation uygulama çalışırken de okunabilsin.
// Jackson bunu çalışma anında okuduğu için bu şart. Yoksa annotation görmezden gelinir.
@Retention(RetentionPolicy.RUNTIME)

// Nereye konabileceğini sınırlar: alan, metot veya parametre üzerine.
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})

// Jackson'a "bu annotation'ın üzerindeki diğer Jackson annotation'larını da uygula" der.
// Bu sayede aşağıdaki @JsonDeserialize, @FlexibleBoolean yazınca otomatik devreye girer.
@JacksonAnnotationsInside

// Asıl bağlantı burada: bu annotation'lı alanı doldururken
// Jackson varsayılan dönüştürücü yerine bizim FlexibleBooleanDeserializer'ı kullansın.
@JsonDeserialize(using = FlexibleBooleanDeserializer.class)

// @interface: Bu bir annotation tanımıdır. İçi boş çünkü sadece "etiket" görevi görüyor.
// Tüm iş yukarıdaki meta-annotation'larla ve deserializer sınıfıyla yapılıyor.
public @interface FlexibleBoolean {
}