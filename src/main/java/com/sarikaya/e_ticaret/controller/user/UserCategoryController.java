package com.sarikaya.e_ticaret.controller.user;

import com.sarikaya.e_ticaret.entity.Category;
import com.sarikaya.e_ticaret.repository.CategoryRepository;
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
    public ResponseEntity<List<Category>> getCategories() {
        return ResponseEntity.ok(categoryRepository.findAll());
    }
}