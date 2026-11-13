package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.auth.entity.RefreshToken;
import com.sarikaya.e_ticaret.auth.repository.RefreshTokenRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Oturum yenileme: tek kullanımlık token, tekrar kullanılırsa bütün oturumlar kapanır. */
class RefreshTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(
            tokens, users, Duration.ofDays(14), true, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyTheHashIsStored() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);

        String raw = service.issue(7L);

        verify(tokens).save(saved.capture());
        assertNotEquals(raw, saved.getValue().getTokenHash());
        assertEquals(RefreshTokenService.hash(raw), saved.getValue().getTokenHash());
        assertEquals(NOW.plus(Duration.ofDays(14)), saved.getValue().getExpiresAt());
    }

    @Test
    void validTokenIsReplacedByANewOne() {
        RefreshToken stored = stored(null, NOW.plusSeconds(60));
        User user = mock(User.class);
        when(user.isEnabled()).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        var rotation = service.rotate("eski").orElseThrow();

        assertEquals(NOW, stored.getRevokedAt()); // eski token artık kullanılamaz
        assertNotNull(rotation.newRawToken());
        assertNotEquals("eski", rotation.newRawToken());
    }

    @Test
    void reusedTokenClosesEverySessionOfTheUser() {
        stored(NOW.minusSeconds(120), NOW.plusSeconds(60)); // 2 dk önce kullanılmış

        assertTrue(service.rotate("eski").isEmpty());
        verify(tokens).revokeAllForUser(7L, NOW);
    }

    @Test
    void tokenUsedSecondsAgoByAnotherTabDoesNotCloseSessions() {
        stored(NOW.minusSeconds(5), NOW.plusSeconds(60)); // diğer sekme az önce yeniledi

        assertTrue(service.rotate("eski").isEmpty());
        verify(tokens, never()).revokeAllForUser(any(), any());
    }

    @Test
    void expiredOrUnknownTokenIsRejected() {
        stored(null, NOW.minusSeconds(1));
        assertTrue(service.rotate("eski").isEmpty());

        when(tokens.findByHashForUpdate(anyString())).thenReturn(Optional.empty());
        assertTrue(service.rotate("bilinmeyen").isEmpty());
        assertTrue(service.rotate(null).isEmpty());
        verify(tokens, never()).revokeAllForUser(any(), any());
    }

    @Test
    void cookieIsHttpOnlyStrictAndScopedToAuthEndpoints() {
        String cookie = service.cookie("abc").toString();

        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api/auth"));
        assertTrue(service.clearingCookie().toString().contains("Max-Age=0"));
    }

    private RefreshToken stored(Instant revokedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUserId(7L);
        token.setTokenHash(RefreshTokenService.hash("eski"));
        token.setRevokedAt(revokedAt);
        token.setExpiresAt(expiresAt);
        when(tokens.findByHashForUpdate(RefreshTokenService.hash("eski"))).thenReturn(Optional.of(token));
        return token;
    }
}
