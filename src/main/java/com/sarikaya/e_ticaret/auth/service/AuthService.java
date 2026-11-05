package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.auth.dto.AuthResponse;
import com.sarikaya.e_ticaret.auth.dto.LoginRequest;
import com.sarikaya.e_ticaret.auth.dto.RegisterRequest;
import com.sarikaya.e_ticaret.exception.UsernameAlreadyExistsException;
import com.sarikaya.e_ticaret.exception.WeakPasswordException;
import com.sarikaya.e_ticaret.audit.entity.LoginHistory;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.audit.repository.LoginHistoryRepository;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        // Şifre boş, boşluklardan oluşan veya 5 karakterden kısa ise kayıt yapılmaz.
        if (request.getPassword() == null || request.getPassword().isBlank()
                || request.getPassword().length() < 5) {
            throw new WeakPasswordException("Şifre en az 5 karakter olmalıdır.");
        }

        // Aynı kullanıcı adı zaten varsa kayıt yapılmaz.
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
                .role(user.getRole().name())
                .message("Kayıt Başarılı")
                .username(user.getUsername())
                .build();
    }

    // Başarılı girişin zamanını saklar; IP ve tarayıcı bilgisi toplanmaz.
    public Object[] login(LoginRequest request) {
        // Yanlış kimlik bilgileri veya pasif hesap için Spring Security uygun hatayı üretir.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword())
        );

        var user = userRepository.findByUsername(request.getUsername()).orElseThrow();

        // Hem son giriş bilgisini hem de yalnızca zaman içeren giriş geçmişini sakla.
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        userRepository.save(user);
        loginHistoryRepository.save(LoginHistory.builder()
                .userId(user.getId())
                .loggedInAt(now)
                .build());

        var jwtToken = jwtService.generateToken(user);

        AuthResponse response = AuthResponse.builder()
                .status("success")
                .role(user.getRole().name())
                .message(user.getRole() == Role.ADMIN ? "admin girişi" : "kullanıcı girişi")
                .username(user.getUsername())
                .build();

        return new Object[]{response, jwtToken};
    }
}