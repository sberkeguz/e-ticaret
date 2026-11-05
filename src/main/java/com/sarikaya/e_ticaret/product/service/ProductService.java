package com.sarikaya.e_ticaret.product.service;

import com.sarikaya.e_ticaret.brand.repository.BrandRepository;
import com.sarikaya.e_ticaret.category.repository.CategoryRepository;
import com.sarikaya.e_ticaret.product.dto.ProductRequest;
import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
                          BrandRepository brandRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
    }

    public List<ProductResponse> listActive() {
        return productRepository.findAllByActiveTrue()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse getActive(Long id) {
        return ProductResponse.from(findActive(id));
    }

    public ProductResponse create(ProductRequest request) {
        Product product = new Product();
        copyRequest(request, product);
        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse update(Long id, ProductRequest request) {
        Product product = findActive(id);
        copyRequest(request, product);
        return ProductResponse.from(productRepository.save(product));
    }

    public void deactivate(Long id) {
        Product product = findActive(id);
        product.setActive(false);
        productRepository.save(product);
    }

    private Product findActive(Long id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Ürün bulunamadı"));
    }

    private void copyRequest(ProductRequest request, Product product) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setImageUrl(request.imageUrl());
        product.setOrigin(request.origin());
        product.setBrand(request.brandId() == null
                ? null
                : brandRepository.findById(request.brandId()).orElse(null));
        product.setCategories(request.categoryIds() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(categoryRepository.findAllById(request.categoryIds())));
    }
}
