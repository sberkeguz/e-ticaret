package com.sarikaya.e_ticaret.security.service;

import com.sarikaya.e_ticaret.exceptions.UsernameAlreadyExistsException;
import com.sarikaya.e_ticaret.exceptions.WeakPasswordException;
import com.sarikaya.e_ticaret.repository.LoginHistoryRepository;
import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.dto.AuthRequest;
import com.sarikaya.e_ticaret.security.dto.AuthResponse;
import com.sarikaya.e_ticaret.security.entity.LoginHistory;
import com.sarikaya.e_ticaret.security.entity.Role;
import com.sarikaya.e_ticaret.security.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;


@Service
// @RequiredArgsConstructor => final olarak tanımlanmış bağımlılıklar için otomatik constructor oluşturur.
// Bu sayede @Autowired yazmamıza gerek kalmaz
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    // Giriş geçmişi kayıtları için
    private final LoginHistoryRepository loginHistoryRepository;

    public AuthResponse register(AuthRequest request) {

        // Şifre boş, boşluklardan oluşan veya 5 karakterden kısa ise kayıt yapılmaz (403)
        if (request.getPassword() == null || request.getPassword().isBlank() || request.getPassword().length() < 5) {
            throw new WeakPasswordException("Şifre en az 5 karakter olmalıdır.");
        }

        // Aynı kullanıcı adı zaten varsa kayıt yapılmaz (403)
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UsernameAlreadyExistsException("Bu kullanıcı adı zaten kayıtlı.");
        }

        var user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();

        userRepository.save(user);

        return AuthResponse.builder()
                .status("success")
                // AuthResponse sınıfı "role" bilgisini String olarak beklediği için
                // user.getRole().name() diyerek Enum değerini (USER/ADMIN) düz metne çeviriyoruz
                .role(user.getRole().name())
                .message("Kayıt Başarılı")
                .username(user.getUsername())
                .build();
    }

    // ip ve userAgent: AuthController'dan gelir, giriş geçmişine yazılır
    public Object[] login(AuthRequest request, String ip, String userAgent) {
        // 1. ADIM: Kullanıcının girdiği isim ve şifreyi AuthenticationManager'a veriyoruz.
        // Yanlış şifre veya olmayan kullanıcı BadCredentialsException fırlatır (handler 401 döner),
        // pasif hesap DisabledException fırlatır (handler 403 döner).
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // veritabanına çekmek
        var user = userRepository.findByUsername(request.getUsername()).orElseThrow();

        // 2. ADIM: Giriş başarılı. Son giriş zamanını güncelle ve geçmişe bir satır ekle.
        // (Buraya sadece şifre doğruysa ulaşılır, başarısız denemeler kaydedilmez.)
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        userRepository.save(user);

        loginHistoryRepository.save(LoginHistory.builder()
                .userId(user.getId())
                .loggedInAt(now)
                .ipAddress(ip)
                // Sütun 255 karakter, uzun tarayıcı bilgisini kes
                .userAgent(userAgent != null && userAgent.length() > 255
                        ? userAgent.substring(0, 255) : userAgent)
                .build());

        // token kesmek
        var jwtToken = jwtService.generateToken(user);

        AuthResponse response = AuthResponse.builder()
                .status("success")
                .role(user.getRole().name())
                .message(user.getRole() == Role.ADMIN ? "admin girişi" : "kullanıcı girişi")
                .username(user.getUsername())
                .build();

        // Hem hazırladığımız JSON yanıtını hem de token'ı Controller'a geri gönderiyoruz.
        return new Object[]{response, jwtToken};
    }
}