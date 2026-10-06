package com.sarikaya.e_ticaret.security.dto;

import com.sarikaya.e_ticaret.annotation.FlexibleBoolean;
import lombok.Data;

@Data
public class AuthRequest {

    private String username;

    private String password;

    @FlexibleBoolean
    private Boolean isActive;

}