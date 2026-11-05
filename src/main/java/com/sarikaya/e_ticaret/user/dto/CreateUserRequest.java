package com.sarikaya.e_ticaret.user.dto;

import com.sarikaya.e_ticaret.annotation.FlexibleBoolean;
import com.sarikaya.e_ticaret.annotation.StrongPassword;
import com.sarikaya.e_ticaret.user.entity.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;


    @NotBlank(message = "Şifre boş olamaz.")
    @StrongPassword
    private String password;


    private Role role;


    @FlexibleBoolean
    private Boolean active;
}