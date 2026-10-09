package com.sarikaya.e_ticaret.controller.admin;

import com.sarikaya.e_ticaret.entity.Category;
import com.sarikaya.e_ticaret.exceptions.ResourceNotFoundException;
import com.sarikaya.e_ticaret.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryRepository categoryRepository;

    // Listeleme
    @GetMapping
    public ResponseEntity<List<Category>> getAllCategories() {
        return ResponseEntity.ok(categoryRepository.findAll());
    }

    // Ekleme
    @PostMapping
    public ResponseEntity<Category> createCategory(@RequestBody Category category){
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    // Silme
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Kategori Bulunamadı (id:" + id + ")");
        }
        categoryRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // Güncelleme
    @PutMapping("/{id}")
    public ResponseEntity<Category> updateCategory(@PathVariable Long id, @RequestBody Category categoryDetails) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kategori bulunamadı (id: " + id + ")"));

        category.setName(categoryDetails.getName());
        return ResponseEntity.ok(categoryRepository.save(category));
    }
}