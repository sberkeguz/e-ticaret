package com.sarikaya.e_ticaret.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Siparişlerim'den, ödemesi bekleyen bir siparişi ödemek için. */
public record PayOrderRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "\\d{11}", message = "T.C. kimlik numarası 11 haneli olmalı.")
        String identityNumber,
        boolean forceThreeDS,
        @NotNull(message = "Kart bilgileri zorunlu.") @Valid CheckoutRequest.Card card) {
}
