package com.sarikaya.e_ticaret.catalog.service;

import com.sarikaya.e_ticaret.catalog.entity.Category;
import com.sarikaya.e_ticaret.catalog.entity.Product;
import com.sarikaya.e_ticaret.catalog.repository.CategoryRepository;
import com.sarikaya.e_ticaret.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    // Hiçbir üst kategoriye bağlı olmayan kök kategorileri getirir.
    public List<Category> getRootCategories() {
        return categoryRepository.findByParentsIsEmpty();
    }

    // Verilen kategoriyi üst kategori olarak kullanan alt kategorileri getirir.
    public List<Category> getSubCategories(Long parentId) {
        return categoryRepository.findDistinctByParents_Id(parentId);
    }

    // Yalnızca bu kategoriye doğrudan bağlı ürünleri getirir.
    public List<Product> getProductsByCategory(Long categoryId) {
        return productRepository.findDistinctByCategories_Id(categoryId);
    }

    // Seçilen kategoriye ve tüm alt kategorilerine bağlı ürünleri getirir.
    public List<Product> getProductsByCategoryTree(Long categoryId) {
        Set<Long> categoryIds = new LinkedHashSet<>();
        collectCategoryIds(categoryId, categoryIds);

        return productRepository.findDistinctByCategories_IdIn(
                new ArrayList<>(categoryIds));
    }

    // Kategoriyi ve alt kategorilerini dolaşarak ID'lerini toplar.
    // Set kullanımı, birden fazla yoldan ulaşılan kategoriyi tekrar eklemeyi önler.
    private void collectCategoryIds(Long categoryId, Set<Long> result) {
        if (!result.add(categoryId)) {
            return;
        }

        List<Category> children =
                categoryRepository.findDistinctByParents_Id(categoryId);

        for (Category child : children) {
            collectCategoryIds(child.getId(), result);
        }
    }
}