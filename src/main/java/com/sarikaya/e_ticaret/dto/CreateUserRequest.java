package com.sarikaya.e_ticaret.dto;

import com.sarikaya.e_ticaret.annotation.FlexibleBoolean;
import com.sarikaya.e_ticaret.annotation.StrongPassword;
import com.sarikaya.e_ticaret.security.entity.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;

    // Şifre zorunlu ve en az 5 karakter
    @NotBlank(message = "Şifre boş olamaz.")
    @StrongPassword
    private String password;

    // Gönderilmezse USER olur
    private Role role;

    // Gönderilmezse true (aktif) olur
    @FlexibleBoolean
    private Boolean active;
}