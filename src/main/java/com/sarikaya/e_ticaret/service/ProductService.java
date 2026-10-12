package com.sarikaya.e_ticaret.service;

import com.sarikaya.e_ticaret.entity.Category;
import com.sarikaya.e_ticaret.entity.Product;
import com.sarikaya.e_ticaret.repository.CategoryRepository;
import com.sarikaya.e_ticaret.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<Category> getRootCategories() {
        return categoryRepository.findByParentIsNull();
    }

    public List<Category> getSubCategories(Long parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    public List<Product> getProductsByCategory(Long categoryId) {
        return productRepository.findByCategoryId(categoryId);
    }

    public List<Product> getProductsByCategoryTree(Long categoryId) {
        List<Long> categoryIds = new ArrayList<>();
        collectCategoryIds(categoryId, categoryIds);

        List<Product> result = new ArrayList<>();
        for (Long id : categoryIds) {
            result.addAll(productRepository.findByCategoryId(id));
        }
        return result;
    }

    private void collectCategoryIds(Long categoryId, List<Long> result) {
        result.add(categoryId);

        List<Category> children = categoryRepository.findByParentId(categoryId);
        for (Category child : children) {
            collectCategoryIds(child.getId(), result);
        }
    }
}