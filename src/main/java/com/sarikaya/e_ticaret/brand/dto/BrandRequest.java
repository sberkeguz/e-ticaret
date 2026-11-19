package com.sarikaya.e_ticaret.brand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Marka ekleme/güncelleme isteği: yalnızca ad alınır (numara gibi alanlar istekten değiştirilemez). */
public record BrandRequest(@NotBlank(message = "Marka adı zorunlu.") @Size(max = 100) String name) {
}
