package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.audit.repository.LoginHistoryRepository;
import com.sarikaya.e_ticaret.auth.dto.LoginRequest;
import com.sarikaya.e_ticaret.auth.dto.RegisterRequest;
import com.sarikaya.e_ticaret.exception.TooManyLoginAttemptsException;
import com.sarikaya.e_ticaret.exception.WeakPasswordException;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceLoginLimitTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LoginHistoryRepository loginHistoryRepository = mock(LoginHistoryRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);

    private final AuthService authService = new AuthService(userRepository, loginHistoryRepository,
            passwordEncoder, jwtService, authenticationManager, loginAttemptService);

    @Test
    void wrongPasswordIsCountedAsFailedAttempt() {
        doThrow(new BadCredentialsException("hatalı")).when(authenticationManager).authenticate(any());

        assertThrows(BadCredentialsException.class, () -> authService.login(loginRequest("berke", "yanlis123")));

        verify(loginAttemptService).loginFailed("berke");
        verify(loginAttemptService, never()).loginSucceeded(any());
    }

    @Test
    void lockedAccountIsRejectedBeforePasswordIsChecked() {
        doThrow(new TooManyLoginAttemptsException("kilitli")).when(loginAttemptService).checkNotLocked("berke");

        assertThrows(TooManyLoginAttemptsException.class, () -> authService.login(loginRequest("berke", "dogru123")));

        verifyNoInteractions(authenticationManager);
    }

    @Test
    void successfulLoginClearsFailedAttempts() {
        User user = User.builder().id(1L).username("berke").role(Role.USER).build();
        when(userRepository.findByUsername("berke")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("token");

        authService.login(loginRequest("berke", "dogru123"));

        verify(loginAttemptService).loginSucceeded("berke");
    }

    @Test
    void registerRejectsWeakPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("yeni");
        request.setPassword("pilot");

        assertThrows(WeakPasswordException.class, () -> authService.register(request));

        verify(userRepository, never()).save(any());
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
