package com.sarikaya.e_ticaret.annotation;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// FlexibleBooleanDeserializer sınıfı, JSON verisindeki farklı formattaki olumlu/olumsuz girdileri
// Java'daki standart Boolean türüne dönüştürmek için oluşturulmuş özel bir sınıftır
// ValueDeserializer<Boolean> sınıfından kalıtım alarak, bu sınıfın bir Jackson dönüştürücüsü olduğu belirtilir
public class FlexibleBooleanDeserializer extends ValueDeserializer<Boolean> {

    // deserialize metodu: Jackson, @FlexibleBoolean ile işaretlenmiş bir alanı JSON'dan okumaya
    // çalıştığında otomatik olarak bu metodu tetikler. Dönüştürme işleminin kalbi burasıdır
    // JsonParser (p): JSON belgesindeki o anki veriyi okumamızı sağlayan araç
    // DeserializationContext (ctxt): Dönüştürme işlemi sırasındaki genel ayarlar, yapılandırmalar ve hata yönetimi için bağlam
    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) {

        // JSON'dan gelen verinin orijinal veri tipi ne olursa olsun (sayı, boolean, karakter vs.)
        // işlem bütünlüğü ve kolaylığı sağlamak için doğrudan String (metin) formatında alınır.
        String value = p.getValueAsString();

        // Eğer JSON'da bu alan için bir değer gönderilmemişse veya null gönderilmişse,
        // NullPointerException hatası almamak için kodun devamı çalıştırılmaz ve null döndürülür.
        if (value == null) {
            return null;
        }

        // Gelen metnin başındaki ve sonundaki boşluklar silinir (trim).
        // Ardından büyük/küçük harf duyarlılığını ortadan kaldırmak için tüm harfler küçültülür (toLowerCase).
        // Böylece " X ", "x" veya "TRUE", "true" gibi veriler standart bir forma sokulur.
        value = value.trim().toLowerCase();

        // Switch expression yapısı kullanılarak, formatlanmış metnin ne anlama geldiği kontrol edilir.
        return switch (value) {
            // Eğer gelen veri "true", "1" veya "x" karakterlerinden biriyse, sistem bunu mantıksal
            // olarak doğru/onaylı kabul eder ve sisteme true olarak kaydeder.
            case "true", "1", "x" -> true;

            // Eğer gelen veri "false", "0" veya boş bir metin ("") ise, sistem bunu mantıksal
            // olarak yanlış/reddedilmiş kabul eder ve sisteme false olarak kaydeder.
            case "false", "0", "" -> false;

            // Eğer beklenen formatların tamamen dışında anlamsız bir metin gelirse (örneğin "evet", "abc"),
            // uygulamanın veri bütünlüğünü bozmamak için işlemi durdurur ve Jackson üzerinden bir hata (exception) fırlatır.
            // Fırlatılan bu hata Spring Boot tarafından yakalanır ve istemciye (frontend/postman) "Geçersiz istek" olarak dönülür.
            default -> throw ctxt.weirdStringException(value, Boolean.class,
                    "Geçersiz değer, kabul edilenler: true, 1, x ve false, 0, boş");
        };
    }
}