<<<<<<<< HEAD:src/main/java/com/sarikaya/e_ticaret/category/controller/UserCategoryController.java
package com.sarikaya.e_ticaret.category.controller;

import com.sarikaya.e_ticaret.category.dto.CategoryResponse;
import com.sarikaya.e_ticaret.category.repository.CategoryRepository;
========
package com.sarikaya.e_ticaret.catalog.controller.user;

import com.sarikaya.e_ticaret.catalog.dto.CategoryResponse;
import com.sarikaya.e_ticaret.catalog.repository.CategoryRepository;
>>>>>>>> origin/master:src/main/java/com/sarikaya/e_ticaret/catalog/controller/user/UserCategoryController.java
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user/categories")
@RequiredArgsConstructor
public class UserCategoryController {

    private final CategoryRepository categoryRepository;

    // Sadece listeleme
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getCategories() {
        List<CategoryResponse> categories = categoryRepository.findAll()
                .stream()
                .map(CategoryResponse::from)
                .toList();

        return ResponseEntity.ok(categories);
    }
}