package com.sarikaya.e_ticaret.service;

import com.sarikaya.e_ticaret.dto.AuthRequest;
import com.sarikaya.e_ticaret.dto.AuthResponse;
import com.sarikaya.e_ticaret.entity.User;
import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
//@RequiredArgsConstructor=> final olanlara metod ekler
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(AuthRequest request){
        var user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("USER")
                .build();
        userRepository.save(user);

        return AuthResponse.builder()
                .status("success")
                .role(user.getRole())
                .message("Kayıt Başarılı")
                .username(user.getUsername())
                .build();
    }
    public Object[] login(AuthRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        var user = userRepository.findByUsername(request.getUsername()).orElseThrow();
        var jwtToken = jwtService.generateToken(user);

        AuthResponse response = AuthResponse.builder()
                .status("success")
                .role(user.getRole())
                .message(user.getRole().equals("ADMIN") ? "admin girişi" : "kullanıcı girişi")
                .username(user.getUsername())
                .build();
        return new Object[]{response, jwtToken};
    }
}
