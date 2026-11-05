package com.sarikaya.e_ticaret.category.dto;

import com.sarikaya.e_ticaret.category.entity.Category;

import java.util.List;

public record CategoryResponse(Long id, String name, List<Long> parentIds) {

    public static CategoryResponse from(Category category) {
        List<Long> parentIds = category.getParents()
                .stream()
                .map(Category::getId)
                .sorted()
                .toList();

        return new CategoryResponse(category.getId(), category.getName(), parentIds);
    }
}