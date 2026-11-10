package com.sarikaya.e_ticaret.annotation;

public final class PasswordRules {

    public static final int MIN_LENGTH = 8;
    public static final String MESSAGE =
            "Şifre en az 8 karakter olmalı, en az bir harf ve bir rakam içermelidir.";

    private PasswordRules() {
    }

    public static boolean isStrong(String password) {
        return isStrong(password, MIN_LENGTH);
    }

    public static boolean isStrong(String password, int minLength) {
        if (password == null || password.isBlank()) {
            return false;
        }
        if (password.length() < minLength) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char character : password.toCharArray()) {
            if (Character.isLetter(character)) {
                hasLetter = true;
            }
            if (Character.isDigit(character)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }
}
