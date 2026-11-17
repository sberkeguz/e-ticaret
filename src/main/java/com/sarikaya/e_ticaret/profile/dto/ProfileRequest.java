package com.sarikaya.e_ticaret.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileRequest(
        @Size(max = 60) String firstName,
        @Size(max = 60) String lastName,
        @Size(max = 20) String phone,
        @Email(message = "Geçerli bir e-posta adresi girin.") @Size(max = 254) String email,
        @Pattern(regexp = "\\s*(\\d{11})?\\s*", message = "T.C. kimlik numarası 11 haneli olmalı.") String identityNumber,
        @Size(max = 60) String city,
        @Size(max = 60) String district,
        @Size(max = 60) String neighborhood,
        @Size(max = 300) String address) {
}
