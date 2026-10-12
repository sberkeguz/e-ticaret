package com.sarikaya.e_ticaret.catalog.service;

import com.sarikaya.e_ticaret.catalog.entity.Category;
import com.sarikaya.e_ticaret.catalog.entity.Product;
import com.sarikaya.e_ticaret.catalog.repository.CategoryRepository;
import com.sarikaya.e_ticaret.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
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
        return productRepository.findDistinctByCategories_Id(categoryId);
    }

    public List<Product> getProductsByCategoryTree(Long categoryId) {
        List<Long> categoryIds = new ArrayList<>();
        collectCategoryIds(categoryId, categoryIds);
        return productRepository.findDistinctByCategories_IdIn(categoryIds);
    }

    private void collectCategoryIds(Long categoryId, List<Long> result) {
        result.add(categoryId);

        List<Category> children = categoryRepository.findByParentId(categoryId);
        for (Category child : children) {
            collectCategoryIds(child.getId(), result);
        }
    }
}