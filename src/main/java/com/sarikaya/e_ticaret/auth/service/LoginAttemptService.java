package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.exception.TooManyLoginAttemptsException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, FailedAttempts> attemptsByUsername = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public void checkNotLocked(String username) {
        String key = keyOf(username);
        FailedAttempts attempts = attemptsByUsername.get(key);
        if (attempts == null || attempts.lockedUntil == null) {
            return;
        }

        Instant now = clock.instant();
        boolean stillLocked = now.isBefore(attempts.lockedUntil);
        if (!stillLocked) {
            attemptsByUsername.remove(key);
            return;
        }

        long secondsLeft = Duration.between(now, attempts.lockedUntil).toSeconds();
        long minutesLeft = Math.max(1, (secondsLeft + 59) / 60);
        throw new TooManyLoginAttemptsException(
                "Çok fazla hatalı giriş denemesi yapıldı. " + minutesLeft + " dakika sonra tekrar deneyin.");
    }

    public void loginFailed(String username) {
        Instant now = clock.instant();
        attemptsByUsername.compute(keyOf(username), (key, current) -> {
            FailedAttempts attempts = current;

            boolean oldFailuresExpired = attempts != null
                    && attempts.lastFailureAt.plus(LOCK_DURATION).isBefore(now);
            if (attempts == null || oldFailuresExpired) {
                attempts = new FailedAttempts();
            }

            attempts.count = attempts.count + 1;
            attempts.lastFailureAt = now;
            if (attempts.count >= MAX_FAILED_ATTEMPTS) {
                attempts.lockedUntil = now.plus(LOCK_DURATION);
            }
            return attempts;
        });
    }

    public void loginSucceeded(String username) {
        attemptsByUsername.remove(keyOf(username));
    }

    private String keyOf(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static class FailedAttempts {
        private int count;
        private Instant lastFailureAt;
        private Instant lockedUntil;
    }
}
