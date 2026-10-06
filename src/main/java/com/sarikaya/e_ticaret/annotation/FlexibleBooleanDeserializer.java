package com.sarikaya.e_ticaret.annotation;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// ValueDeserializer<Boolean>: Jackson'a "JSON'dan gelen değeri Boolean'a çevirecek özel bir sınıfım var" demenin yolu.
// Jackson 2'de bunun adı JsonDeserializer'dı, Jackson 3'te ValueDeserializer oldu.
public class FlexibleBooleanDeserializer extends ValueDeserializer<Boolean> {

    // Jackson, bu annotation'lı alanı doldururken bu metodu çağırır.
    // p    -> JSON'daki ham değeri okumamızı sağlayan parser
    // ctxt -> hata fırlatmak gibi işler için kullanılan bağlam
    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) {

        // Gelen değeri her durumda metin olarak al.
        // Böylece "1" (metin), 1 (sayı) ve true (boolean) hepsi aynı şekilde okunur.
        String value = p.getValueAsString();

        // Değer yoksa null döndür
        if (value == null) {
            return null;
        }

        // Baştaki/sondaki boşlukları sil, harfleri küçült.
        // Böylece " X " ve "x" aynı kabul edilir.
        // " " (tek boşluk) trim edilince "" olur, aşağıda false sayılır.
        value = value.trim().toLowerCase();

        return switch (value) {
            // Bu değerlerden biri geldiyse true
            case "true", "1", "x" -> true;

            // Bu değerlerden biri geldiyse false ("" = boş veya sadece boşluk)
            case "false", "0", "" -> false;

            // Tanımadığımız bir değer gelirse (ör. "abc") sessizce false yapmak yerine
            // hata fırlat. Yanlış veri fark edilmeden sisteme girmesin.
            // Spring bu hatayı 400 Bad Request olarak istemciye döner.
            default -> throw ctxt.weirdStringException(value, Boolean.class,
                    "Geçersiz değer, kabul edilenler: true, 1, x ve false, 0, boş");
        };
    }
}