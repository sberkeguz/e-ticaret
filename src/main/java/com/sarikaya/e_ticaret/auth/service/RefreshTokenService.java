package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.auth.entity.RefreshToken;
import com.sarikaya.e_ticaret.auth.repository.RefreshTokenRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Oturum yenileme. Kısa ömürlü erişim token'ı (JWT) yalnızca tarayıcının belleğinde durur; süresi
 * dolunca bu servisten yenisi alınır. Yenileme token'ı HttpOnly çerezde taşınır (JavaScript
 * okuyamaz, XSS ile çalınamaz) ve her kullanımda yenisiyle değiştirilir.
 */
@Service
public class RefreshTokenService {
    public static final String COOKIE = "refresh_token";
    /** Çerez yalnızca oturum uçlarına gider; diğer isteklerle taşınmaz. */
    static final String COOKIE_PATH = "/api/auth";
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Az önce kullanılmış token bu süre içinde tekrar gelirse çalınma sayılmaz (eşzamanlı sekmeler). */
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final Duration lifetime;
    private final boolean secureCookie;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users,
                               @Value("${auth.refresh-token-days:14}") long days,
                               @Value("${auth.refresh-cookie-secure:true}") boolean secureCookie) {
        this(tokens, users, Duration.ofDays(days), secureCookie, Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, Duration lifetime,
                        boolean secureCookie, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.lifetime = lifetime;
        this.secureCookie = secureCookie;
        this.clock = clock;
    }

    /** Yeni oturum açar; çereze konacak ham token'ı döner. */
    @Transactional
    public String issue(Long userId) {
        String raw = newRawToken();
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(raw));
        token.setCreatedAt(clock.instant());
        token.setExpiresAt(clock.instant().plus(lifetime));
        tokens.save(token);
        return raw;
    }

    /** Başarılı yenilemenin sonucu: kullanıcı ve yerine verilen yeni ham token. */
    public record Rotation(User user, String newRawToken) {
    }

    /**
     * Token'ı kullanır ve yenisini verir. Geçersiz/süresi dolmuşsa boş döner. Daha önce kullanılmış
     * (iptal edilmiş) bir token gelirse çalınmış sayılır: o kullanıcının bütün oturumları kapanır.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public Optional<Rotation> rotate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<RefreshToken> found = tokens.findByHashForUpdate(hash(raw));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();
        Instant now = clock.instant();
        if (token.getRevokedAt() != null) {
            if (token.getRevokedAt().isAfter(now.minus(REUSE_GRACE))) {
                // Aynı tarayıcının iki sekmesi aynı anda yeniledi: saldırı değil. Bu istek reddedilir,
                // sekme biraz sonra (tarayıcıya yeni çerez gelmiş olarak) tekrar dener.
                return Optional.empty();
            }
            int closed = tokens.revokeAllForUser(token.getUserId(), now);
            log.warn("Kullanılmış yenileme token'ı tekrar geldi (kullanıcı {}): {} oturum kapatıldı", token.getUserId(), closed);
            return Optional.empty();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Optional<User> user = users.findById(token.getUserId()).filter(User::isEnabled);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        token.setRevokedAt(now);
        return Optional.of(new Rotation(user.get(), issue(token.getUserId())));
    }

    /** Çıkış: bu cihazın oturumunu kapatır. */
    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        tokens.findByHashForUpdate(hash(raw)).filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> token.setRevokedAt(clock.instant()));
    }

    public ResponseCookie cookie(String raw) {
        return baseCookie(raw).maxAge(lifetime).build();
    }

    public ResponseCookie clearingCookie() {
        return baseCookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }

    /** Süresi bir günden uzun süre önce dolmuş kayıtlar her gece silinir. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteExpired() {
        int deleted = tokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        if (deleted > 0) {
            log.info("{} süresi dolmuş yenileme token'ı silindi", deleted);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
