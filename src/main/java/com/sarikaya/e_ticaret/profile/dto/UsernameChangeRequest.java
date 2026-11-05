package com.sarikaya.e_ticaret.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsernameChangeRequest(
        @NotBlank(message = "Kullanıcı adı boş olamaz.")
        @Size(max = 60, message = "Kullanıcı adı en fazla 60 karakter olabilir.")
        String username,
        @NotBlank(message = "Mevcut şifrenizi girin.")
        String currentPassword) {
}
