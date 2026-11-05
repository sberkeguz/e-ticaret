package com.sarikaya.e_ticaret.product.dto;

import com.sarikaya.e_ticaret.product.entity.Product;

import com.sarikaya.e_ticaret.category.entity.Category;
import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        String imageUrl,
        String origin,
        Long brandId,
        String brandName,
        List<Long> categoryIds,
        List<String> categoryNames
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getImageUrl(),
                product.getOrigin(),
                product.getBrand() == null ? null : product.getBrand().getId(),
                product.getBrand() == null ? null : product.getBrand().getName(),
                product.getCategories().stream().map(Category::getId).sorted().toList(),
                product.getCategories().stream().map(Category::getName).sorted().toList()
        );
    }
}
