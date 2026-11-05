package com.sarikaya.e_ticaret.profile.controller;

import com.sarikaya.e_ticaret.auth.service.JwtService;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.UsernameAlreadyExistsException;
import com.sarikaya.e_ticaret.profile.dto.ProfileRequest;
import com.sarikaya.e_ticaret.profile.dto.ProfileResponse;
import com.sarikaya.e_ticaret.profile.dto.UsernameChangeRequest;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal User user) {
        return ProfileResponse.from(user);
    }

    @PutMapping
    public ProfileResponse updateProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ProfileRequest request) {
        user.setFirstName(normalize(request.firstName()));
        user.setLastName(normalize(request.lastName()));
        user.setPhone(normalize(request.phone()));
        user.setCity(normalize(request.city()));
        user.setDistrict(normalize(request.district()));
        user.setNeighborhood(normalize(request.neighborhood()));
        user.setAddress(normalize(request.address()));
        return ProfileResponse.from(userRepository.save(user));
    }

    @PutMapping("/username")
    public ResponseEntity<ProfileResponse> changeUsername(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UsernameChangeRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BadRequestException("Mevcut şifreniz hatalı.");
        }

        String username = normalize(request.username());
        if (!username.equals(user.getUsername())) {
            if (userRepository.existsByUsername(username)) {
                throw new UsernameAlreadyExistsException("Bu kullanıcı adı zaten kayıtlı.");
            }
            user.setUsername(username);
        }

        User savedUser = userRepository.save(user);
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(savedUser))
                .body(ProfileResponse.from(savedUser));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
