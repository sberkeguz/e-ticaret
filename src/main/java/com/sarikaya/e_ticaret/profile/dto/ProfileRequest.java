package com.sarikaya.e_ticaret.profile.dto;

import jakarta.validation.constraints.Size;

public record ProfileRequest(
        @Size(max = 60) String firstName,
        @Size(max = 60) String lastName,
        @Size(max = 20) String phone,
        @Size(max = 60) String city,
        @Size(max = 60) String district,
        @Size(max = 60) String neighborhood,
        @Size(max = 300) String address) {
}
