package com.sarikaya.e_ticaret.dto;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
//Yeni nesne oluştururken AuthResponse.builder().status("success").build()
//okunaklıyapı

public class AuthResponse {
    private String status;
    private String role;
    private String message;
    private String username;
}
