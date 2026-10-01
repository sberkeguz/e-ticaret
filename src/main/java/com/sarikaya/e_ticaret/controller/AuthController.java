package com.sarikaya.e_ticaret.controller;

import com.sarikaya.e_ticaret.dto.AuthRequest;
import com.sarikaya.e_ticaret.dto.AuthResponse;
import com.sarikaya.e_ticaret.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
//rest api denetleyicisi
@RequestMapping("/api/auth")
//finallere constructor
@RequiredArgsConstructor

public class AuthController {

    private final AuthService authService;
//sadece http post isteklerini bu metoda yönlendirir

    @PostMapping("register")
    //Gelen HTTP isteğinin Body kısmındaki JSON verisini alır
    //ve bizim Java sınıfımız olan AuthRequest nesnesine dönüştürür
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request){
        return ResponseEntity.ok(authService.register(request));
    }

@PostMapping("login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request){
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