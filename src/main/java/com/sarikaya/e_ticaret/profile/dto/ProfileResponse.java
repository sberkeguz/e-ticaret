package com.sarikaya.e_ticaret.profile.dto;

import com.sarikaya.e_ticaret.user.entity.User;

public record ProfileResponse(
        String username,
        String role,
        String firstName,
        String lastName,
        String phone,
        String email,
        String identityNumber,
        String city,
        String district,
        String neighborhood,
        String address) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(
                user.getUsername(),
                user.getRole().name(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getEmail(),
                user.getIdentityNumber(),
                user.getCity(),
                user.getDistrict(),
                user.getNeighborhood(),
                user.getAddress());
    }
}
