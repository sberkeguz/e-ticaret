package com.sarikaya.e_ticaret.profile.controller;

import com.sarikaya.e_ticaret.auth.service.JwtService;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.UsernameAlreadyExistsException;
import com.sarikaya.e_ticaret.profile.dto.ProfileRequest;
import com.sarikaya.e_ticaret.profile.dto.UsernameChangeRequest;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final ProfileController controller =
            new ProfileController(userRepository, jwtService, passwordEncoder);

    @Test
    void updateProfileNormalizesFieldsAndSaves() {
        User user = User.builder().username("tester").role(Role.USER).build();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.updateProfile(user, new ProfileRequest(
                " Ayşe ", " Yılmaz ", " 05551234567 ",
                " İstanbul ", " Kadıköy ", " Caferağa ", " Adres 1 "));

        assertEquals("Ayşe", response.firstName());
        assertEquals("Yılmaz", response.lastName());
        assertEquals("İstanbul", response.city());
        assertEquals("Adres 1", response.address());
        verify(userRepository).save(user);
    }

    @Test
    void changeUsernameChecksPasswordAndReturnsReplacementToken() {
        User user = User.builder().username("old-name").password("encoded").role(Role.USER).build();
        when(passwordEncoder.matches("correct-password", "encoded")).thenReturn(true);
        when(userRepository.existsByUsername("new-name")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken(user)).thenReturn("replacement-token");

        var response = controller.changeUsername(user,
                new UsernameChangeRequest(" new-name ", "correct-password"));

        assertEquals("new-name", response.getBody().username());
        assertEquals("Bearer replacement-token", response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void changeUsernameRejectsIncorrectPasswordWithoutSaving() {
        User user = User.builder().username("old-name").password("encoded").role(Role.USER).build();
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        assertThrows(BadRequestException.class, () -> controller.changeUsername(
                user, new UsernameChangeRequest("new-name", "wrong-password")));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changeUsernameRejectsDuplicateNameWithoutSaving() {
        User user = User.builder().username("old-name").password("encoded").role(Role.USER).build();
        when(passwordEncoder.matches("correct-password", "encoded")).thenReturn(true);
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        assertThrows(UsernameAlreadyExistsException.class, () -> controller.changeUsername(
                user, new UsernameChangeRequest("taken", "correct-password")));
        verify(userRepository, never()).save(any(User.class));
    }
}
