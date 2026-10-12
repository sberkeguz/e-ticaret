package com.sarikaya.e_ticaret.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;

    @NotBlank(message = "Şifre boş olamaz.")
    private String password;
}