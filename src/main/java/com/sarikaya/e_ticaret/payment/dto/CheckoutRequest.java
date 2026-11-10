package com.sarikaya.e_ticaret.payment.dto;

import com.sarikaya.e_ticaret.order.dto.OrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CheckoutRequest(
        @NotBlank @Size(max = 60) String firstName,
        @NotBlank @Size(max = 60) String lastName,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "\\d{11}", message = "T.C. kimlik numarası 11 haneli olmalı.")
        String identityNumber,
        @NotBlank @Size(max = 60) String city,
        @NotBlank @Size(max = 60) String district,
        @NotBlank @Size(max = 60) String neighborhood,
        @NotBlank @Size(max = 300) String address,
        @NotEmpty @Size(max = 100) @Valid List<OrderRequest.Item> items,
        boolean forceThreeDS,
        @NotNull(message = "Kart bilgileri zorunlu.") @Valid Card card) {

    /**
     * Kart bilgileri yalnızca iyzico'ya iletilir; veritabanına yazılmaz ve loglanmaz.
     */
    public record Card(
            @NotBlank(message = "Kart üzerindeki isim zorunlu.") @Size(max = 100) String holderName,
            @NotBlank @Pattern(regexp = "\\d{15,19}", message = "Kart numarası geçersiz.") String number,
            @NotBlank @Pattern(regexp = "0[1-9]|1[0-2]", message = "Son kullanma ayı geçersiz.") String expireMonth,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "Son kullanma yılı geçersiz.") String expireYear,
            @NotBlank @Pattern(regexp = "\\d{3,4}", message = "CVC geçersiz.") String cvc) {

        @Override
        public String toString() {
            String last4 = number == null || number.length() < 4 ? "" : number.substring(number.length() - 4);
            return "Card[**** " + last4 + "]";
        }
    }
}
