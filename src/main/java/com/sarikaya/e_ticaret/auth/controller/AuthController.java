package com.sarikaya.e_ticaret.auth.controller;

import com.sarikaya.e_ticaret.auth.dto.AuthResponse;
import com.sarikaya.e_ticaret.auth.dto.LoginRequest;
import com.sarikaya.e_ticaret.auth.dto.RegisterRequest;
import com.sarikaya.e_ticaret.auth.service.AuthService;
import com.sarikaya.e_ticaret.auth.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AuthService authService;
    private final RefreshTokenService refreshTokens;

    @PostMapping("register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, CsrfToken csrfToken, HttpServletResponse servletResponse) {
        // CSRF token'ı üretilir ve XSRF-TOKEN çerezi olarak gönderilir; yenileme/çıkış istekleri onu ister.
        csrfToken.getToken();
        Object[] loginResult = authService.login(request);
        AuthResponse response = (AuthResponse) loginResult[0];
        String token = (String) loginResult[1];
        Long userId = (Long) loginResult[2];

        addCookie(servletResponse, refreshTokens.cookie(refreshTokens.issue(userId)));
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(response);
    }

    /** Yenileme çereziyle yeni erişim token'ı verir; çerez de yenisiyle değişir. */
    @PostMapping("refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshTokenService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse servletResponse) {
        // CSRF denetimi (X-XSRF-TOKEN başlığı) Spring Security'de, bu metoda gelmeden yapılır.
        return refreshTokens.rotate(refreshToken)
                .map(rotation -> {
                    addCookie(servletResponse, refreshTokens.cookie(rotation.newRawToken()));
                    return ResponseEntity.ok()
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.accessTokenFor(rotation.user()))
                            .body(authService.responseFor(rotation.user()));
                })
                .orElseGet(() -> {
                    addCookie(servletResponse, refreshTokens.clearingCookie());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                });
    }

    /** Çıkış: bu cihazın oturumu sunucuda da kapanır, çerez silinir. */
    @PostMapping("logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse servletResponse) {
        refreshTokens.revoke(refreshToken);
        addCookie(servletResponse, refreshTokens.clearingCookie());
        return ResponseEntity.noContent().build();
    }

    /**
     * Çerez yanıta doğrudan eklenir. ResponseEntity'nin Set-Cookie başlığı, Spring Security'nin aynı
     * yanıta eklediği XSRF-TOKEN çerezinin üzerine yazardı (başlık değiştirilir, eklenmez).
     */
    private static void addCookie(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
