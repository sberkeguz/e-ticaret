package com.sarikaya.e_ticaret.dto;

import com.sarikaya.e_ticaret.annotation.FlexibleBoolean;
import com.sarikaya.e_ticaret.annotation.StrongPassword;
import com.sarikaya.e_ticaret.security.entity.Role;
import lombok.Data;

@Data
public class UpdateUserRequest {

    private String username;

    // Boş bırakılırsa şifre değişmez, doluysa en az 5 karakter olmalı
    @StrongPassword
    private String password;

    private Role role;

    @FlexibleBoolean
    private Boolean active;
}