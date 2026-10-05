package com.sarikaya.e_ticaret.security.auth;

import lombok.Data;

// @Data: Lombok kütüphanesi komutudur.
// Arka planda otomatik olarak getter, setter, toString ve equals metodlarını oluşturur.
@Data
public class AuthRequest {

    private String username;

    private String password;

    // Dışarıdan (Postman/Frontend üzerinden) hesabı pasif olarak açmak istersek diye eklediğimiz alan.
    // Spring Boot JSON'dan gelen true/false değerlerini buraya otomatik dönüştürür, ekstra bir anotasyona gerek yoktur.
    private Boolean isActive;

}