package com.sarikaya.e_ticaret.exception;

import org.springframework.http.HttpStatus;

public class TooManyLoginAttemptsException extends ApiException {

    public TooManyLoginAttemptsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS", message);
    }
}
