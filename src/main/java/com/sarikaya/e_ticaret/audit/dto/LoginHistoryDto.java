package com.sarikaya.e_ticaret.audit.dto;

import com.sarikaya.e_ticaret.audit.entity.LoginHistory;

import java.time.Instant;

// Yalnızca yönetim panelinde gösterilecek giriş zamanı ve kayıt kimliği döndürülür.
public record LoginHistoryDto(Long id, Instant loggedInAt) {

    public static LoginHistoryDto from(LoginHistory history) {
        return new LoginHistoryDto(history.getId(), history.getLoggedInAt());
    }
}