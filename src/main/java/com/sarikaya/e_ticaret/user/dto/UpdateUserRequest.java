package com.sarikaya.e_ticaret.user.dto;

import com.sarikaya.e_ticaret.common.annotation.FlexibleBoolean;
import com.sarikaya.e_ticaret.common.annotation.StrongPassword;
import com.sarikaya.e_ticaret.user.entity.Role;
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