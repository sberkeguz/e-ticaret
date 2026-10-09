package com.sarikaya.e_ticaret.security.controller;

import com.sarikaya.e_ticaret.security.dto.AuthRequest;
import com.sarikaya.e_ticaret.security.dto.AuthResponse;
import com.sarikaya.e_ticaret.security.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
// rest api denetleyicisi
@RequestMapping("/api/auth")
// finallere constructor
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // sadece http post isteklerini bu metoda yönlendirir
    @PostMapping("register")
    // Gelen HTTP isteğinin Body kısmındaki JSON verisini alır
    // ve bizim Java sınıfımız olan AuthRequest nesnesine dönüştürür
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    // HttpServletRequest: isteği yapanın IP adresini ve tarayıcı bilgisini okumak için.
    // Bunlar giriş geçmişine kaydedilir.
    @PostMapping("login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request,
                                              HttpServletRequest httpRequest) {
        Object[] loginResult = authService.login(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"));
        AuthResponse response = (AuthResponse) loginResult[0];
        String token = (String) loginResult[1];

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        return ResponseEntity.ok()
                .headers(headers)
                .body(response);
    }
}