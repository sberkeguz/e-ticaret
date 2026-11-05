package com.sarikaya.e_ticaret.audit.dto;

import com.sarikaya.e_ticaret.audit.entity.LoginHistory;

import java.time.LocalDateTime;

// Yalnızca yönetim panelinde gösterilecek giriş zamanı ve kayıt kimliği döndürülür.
public record LoginHistoryDto(Long id, LocalDateTime loggedInAt) {

    public static LoginHistoryDto from(LoginHistory history) {
        return new LoginHistoryDto(history.getId(), history.getLoggedInAt());
    }
}