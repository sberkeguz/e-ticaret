package com.sarikaya.e_ticaret.auth.dto;

import com.sarikaya.e_ticaret.annotation.FlexibleBoolean;
import com.sarikaya.e_ticaret.annotation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;

    @NotBlank(message = "Şifre boş olamaz.")
    @StrongPassword
    private String password;

    @FlexibleBoolean
    private Boolean isActive;
}