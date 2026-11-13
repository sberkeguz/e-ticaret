package com.sarikaya.e_ticaret.auth.controller;

import com.sarikaya.e_ticaret.auth.dto.AuthResponse;
import com.sarikaya.e_ticaret.auth.dto.LoginRequest;
import com.sarikaya.e_ticaret.auth.dto.RegisterRequest;
import com.sarikaya.e_ticaret.auth.service.AuthService;
import com.sarikaya.e_ticaret.auth.service.RefreshTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Giriş, oturum yenileme ve çıkış. Erişim token'ı (15 dk) Authorization başlığında döner ve
 * tarayıcının yalnızca belleğinde tutulur; yenileme token'ı HttpOnly çerezde taşınır.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    /**
     * Çerezle çalışan uçlar bu başlığı ister. Başka bir siteden gönderilen form bu başlığı
     * koyamaz (SameSite=Strict'e ek ikinci bir CSRF önlemi).
     */
    static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService authService;
    private final RefreshTokenService refreshTokens;

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
        Long userId = (Long) loginResult[2];

        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.SET_COOKIE, refreshTokens.cookie(refreshTokens.issue(userId)).toString())
                .body(response);
    }

    /** Yenileme çereziyle yeni erişim token'ı verir; çerez de yenisiyle değişir. */
    @PostMapping("refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshTokenService.COOKIE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith) {
        if (requestedWith == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return refreshTokens.rotate(refreshToken)
                .map(rotation -> ResponseEntity.ok()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.accessTokenFor(rotation.user()))
                        .header(HttpHeaders.SET_COOKIE, refreshTokens.cookie(rotation.newRawToken()).toString())
                        .body(authService.responseFor(rotation.user())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.SET_COOKIE, refreshTokens.clearingCookie().toString())
                        .build());
    }

    /** Çıkış: bu cihazın oturumu sunucuda da kapanır, çerez silinir. */
    @PostMapping("logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenService.COOKIE, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith) {
        if (requestedWith == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        refreshTokens.revoke(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokens.clearingCookie().toString())
                .build();
    }
}
