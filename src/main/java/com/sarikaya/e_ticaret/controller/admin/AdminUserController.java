package com.sarikaya.e_ticaret.controller.admin;

import com.sarikaya.e_ticaret.dto.CreateUserRequest;
import com.sarikaya.e_ticaret.dto.LoginHistoryDto;
import com.sarikaya.e_ticaret.dto.UpdateUserRequest;
import com.sarikaya.e_ticaret.dto.UserDto;
import com.sarikaya.e_ticaret.exceptions.ForbiddenOperationException;
import com.sarikaya.e_ticaret.exceptions.ResourceNotFoundException;
import com.sarikaya.e_ticaret.exceptions.UsernameAlreadyExistsException;
import com.sarikaya.e_ticaret.repository.LoginHistoryRepository;
import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.entity.Role;
import com.sarikaya.e_ticaret.security.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginHistoryRepository loginHistoryRepository;

    // listele: GET /api/admin/users
    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll().stream().map(UserDto::from).toList());
    }

    // ekle: POST /api/admin/users
    // @Valid: CreateUserRequest içindeki kuralları (@NotBlank, @StrongPassword) çalıştırır.
    // Kural ihlalinde metoda girilmez, GlobalExceptionHandler hata döner.
    @PostMapping
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody CreateUserRequest request) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("Bu kullanıcı adı zaten kayıtlı.");
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : Role.USER)
                .isActive(request.getActive() != null ? request.getActive() : true)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(UserDto.from(userRepository.save(user)));
    }

    // bilgileri güncelle: PUT /api/admin/users/{id}
    // @AuthenticationPrincipal: JwtAuthFilter'ın tanıttığı, o an giriş yapmış admin
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id,
                                              @Valid @RequestBody UpdateUserRequest request,
                                              @AuthenticationPrincipal User currentUser) {
        User user = findUser(id);
        boolean isSelf = user.getId().equals(currentUser.getId());

        // Kullanıcı adı değişiyorsa başkasında olmamalı
        if (request.getUsername() != null && !request.getUsername().isBlank()
                && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new UsernameAlreadyExistsException("Bu kullanıcı adı zaten kayıtlı.");
            }
            user.setUsername(request.getUsername().trim());
        }

        // Şifre gönderildiyse kodla ve kaydet (uzunluk kontrolünü @StrongPassword yaptı)
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        // Admin kendi rolünü düşüremesin (kimse admin kalmayabilir)
        if (request.getRole() != null) {
            if (isSelf && request.getRole() != user.getRole()) {
                throw new ForbiddenOperationException("Kendi rolünüzü değiştiremezsiniz.");
            }
            user.setRole(request.getRole());
        }

        applyActive(user, request.getActive(), isSelf);

        return ResponseEntity.ok(UserDto.from(userRepository.save(user)));
    }

    // aktif/pasif yap: PATCH /api/admin/users/{id}/active  body: {"active": false}
    // Burada @Valid yok: sadece "active" alanı kullanılıyor.
    @PatchMapping("/{id}/active")
    public ResponseEntity<UserDto> setActive(@PathVariable Long id,
                                             @RequestBody UpdateUserRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        User user = findUser(id);
        applyActive(user, request.getActive(), user.getId().equals(currentUser.getId()));
        return ResponseEntity.ok(UserDto.from(userRepository.save(user)));
    }

    // sil: DELETE /api/admin/users/{id}
    // @Transactional: giriş geçmişi ve kullanıcı silme ya birlikte olur ya hiç olmaz
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id,
                                           @AuthenticationPrincipal User currentUser) {
        User user = findUser(id);
        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Kendi hesabınızı silemezsiniz.");
        }
        loginHistoryRepository.deleteByUserId(user.getId());
        userRepository.delete(user);
        return ResponseEntity.noContent().build();
    }

    // giriş geçmişi: GET /api/admin/users/{id}/logins (son 50 giriş)
    @GetMapping("/{id}/logins")
    public ResponseEntity<List<LoginHistoryDto>> getLogins(@PathVariable Long id) {
        findUser(id); // kullanıcı yoksa 404
        return ResponseEntity.ok(loginHistoryRepository
                .findTop50ByUserIdOrderByLoggedInAtDesc(id)
                .stream().map(LoginHistoryDto::from).toList());
    }

    private void applyActive(User user, Boolean active, boolean isSelf) {
        if (active == null) return;
        if (isSelf && !active) {
            throw new ForbiddenOperationException("Kendi hesabınızı pasif yapamazsınız.");
        }
        user.setActive(active);
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı (id: " + id + ")"));
    }
}