package com.sarikaya.e_ticaret.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record OrderRequest(
        @NotBlank @Size(max = 60) 
        String firstName,
        @NotBlank @Size(max = 60) 
        String lastName,
        @NotBlank @Size(max = 20) 
        String phone,
        @NotBlank @Size(max = 60) 
        String city,
        @NotBlank @Size(max = 60) 
        String district,
        @NotBlank @Size(max = 60) String neighborhood,
        @NotBlank @Size(max = 300) 
        String address,
        @NotEmpty @Valid List<Item> items) {


    public record Item(@NotNull Long productId, @Min(1) @Max(99) int quantity) {}
}