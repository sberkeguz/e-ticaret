package com.sarikaya.e_ticaret.auth.dto;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private String status;
    private String role;
    private String message;
    private String username;
}
