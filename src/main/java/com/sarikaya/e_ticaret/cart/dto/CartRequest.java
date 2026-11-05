package com.sarikaya.e_ticaret.cart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CartRequest(
        @NotNull
        @Size(max = 100)
        List<@Valid Item> items) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) Integer quantity) {
    }
}
