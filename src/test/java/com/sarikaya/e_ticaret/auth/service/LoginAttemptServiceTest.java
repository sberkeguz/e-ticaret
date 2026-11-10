package com.sarikaya.e_ticaret.auth.service;

import com.sarikaya.e_ticaret.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    private final MovableClock clock = new MovableClock();
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void fourWrongPasswordsDoNotLockTheAccount() {
        failTimes("berke", 4);

        assertDoesNotThrow(() -> service.checkNotLocked("berke"));
    }

    @Test
    void fifthWrongPasswordLocksTheAccount() {
        failTimes("berke", 5);

        TooManyLoginAttemptsException error = assertThrows(TooManyLoginAttemptsException.class,
                () -> service.checkNotLocked("berke"));
        assertTrue(error.getMessage().contains("15 dakika"));
    }

    @Test
    void lockEndsAfterFifteenMinutes() {
        failTimes("berke", 5);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertDoesNotThrow(() -> service.checkNotLocked("berke"));
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        failTimes("berke", 4);
        service.loginSucceeded("berke");
        failTimes("berke", 4);

        assertDoesNotThrow(() -> service.checkNotLocked("berke"));
    }

    @Test
    void oldFailuresAreForgotten() {
        failTimes("berke", 4);
        clock.advance(Duration.ofMinutes(16));
        failTimes("berke", 1);

        assertDoesNotThrow(() -> service.checkNotLocked("berke"));
    }

    @Test
    void usernameIsCaseInsensitiveAndAccountsAreIndependent() {
        failTimes("Berke", 3);
        failTimes(" berke ", 2);

        assertThrows(TooManyLoginAttemptsException.class, () -> service.checkNotLocked("BERKE"));
        assertDoesNotThrow(() -> service.checkNotLocked("pilot"));
    }

    private void failTimes(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.loginFailed(username);
        }
    }

    private static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
