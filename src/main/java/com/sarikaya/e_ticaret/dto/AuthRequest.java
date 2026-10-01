package com.sarikaya.e_ticaret.dto;


import lombok.Data;
//@data: Lombok kütüphanesi
//otomatik getter setter toString ve equals metodlarını oluşturur
@Data
public class AuthRequest {
    private String username;
    private String password;
}