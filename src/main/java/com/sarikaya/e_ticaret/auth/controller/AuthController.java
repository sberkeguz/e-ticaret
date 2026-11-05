package com.sarikaya.e_ticaret.auth.controller;

import com.sarikaya.e_ticaret.auth.dto.AuthResponse;
import com.sarikaya.e_ticaret.auth.dto.LoginRequest;
import com.sarikaya.e_ticaret.auth.dto.RegisterRequest;
import com.sarikaya.e_ticaret.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {
        Object[] loginResult = authService.login(request);
        AuthResponse response = (AuthResponse) loginResult[0];
        String token = (String) loginResult[1];

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        return ResponseEntity.ok()
                .headers(headers)
                .body(response);
    }
}