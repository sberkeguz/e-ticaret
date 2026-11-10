package com.sarikaya.e_ticaret.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordRulesTest {

    @Test
    void acceptsPasswordWithEightCharactersLettersAndDigits() {
        assertTrue(PasswordRules.isStrong("elma1234"));
        assertTrue(PasswordRules.isStrong("Şeftali9"));
        assertTrue(PasswordRules.isStrong("CtoHLTvO8iP2!7"));
    }

    @Test
    void rejectsShortPassword() {
        assertFalse(PasswordRules.isStrong("ab12"));
        assertFalse(PasswordRules.isStrong("pilot"));
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        assertFalse(PasswordRules.isStrong("sadeceharf"));
    }

    @Test
    void rejectsPasswordWithoutLetter() {
        assertFalse(PasswordRules.isStrong("12345678"));
    }

    @Test
    void rejectsEmptyOrBlankPassword() {
        assertFalse(PasswordRules.isStrong(null));
        assertFalse(PasswordRules.isStrong(""));
        assertFalse(PasswordRules.isStrong("        "));
    }

    @Test
    void validatorAllowsMissingPasswordSoUpdatesCanKeepTheOldOne() {
        StrongPasswordValidator validator = new StrongPasswordValidator();
        validator.initialize(new StrongPassword() {
            public String message() { return PasswordRules.MESSAGE; }
            public Class<?>[] groups() { return new Class<?>[0]; }
            @SuppressWarnings("unchecked")
            public Class<? extends jakarta.validation.Payload>[] payload() { return new Class[0]; }
            public int min() { return PasswordRules.MIN_LENGTH; }
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return StrongPassword.class; }
        });

        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("elma1234", null));
        assertFalse(validator.isValid("pilot", null));
    }
}
