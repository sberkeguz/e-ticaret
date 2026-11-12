package com.sarikaya.e_ticaret.product.service;

import com.sarikaya.e_ticaret.brand.repository.BrandRepository;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.category.repository.CategoryRepository;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.product.dto.ProductRequest;
import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final CartItemRepository cartItems;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
                          BrandRepository brandRepository, CartItemRepository cartItems) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.cartItems = cartItems;
    }

    public List<ProductResponse> listActive(boolean includeTestProducts) {
        List<Product> products;
        if (includeTestProducts) {
            products = productRepository.findAllByActiveTrue();
        } else {
            products = productRepository.findAllByActiveTrueAndTestProductFalse();
        }

        return products.stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse getActive(Long id, boolean includeTestProducts) {
        Product product = findActive(id);

        boolean hiddenFromThisUser = product.isTestProduct() && !includeTestProducts;
        if (hiddenFromThisUser) {
            throw new ResourceNotFoundException("Ürün bulunamadı.");
        }

        return ProductResponse.from(product);
    }

    public ProductResponse create(ProductRequest request) {
        Product product = new Product();
        copyRequest(request, product);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = findActive(id);
        copyRequest(request, product);
        Product saved = productRepository.save(product);
        // Stok düşürüldüyse sepetler yeni stoğa uydurulur (tükendiyse ürün sepetlerden çıkar).
        cartItems.fitToStock(saved.getId(), saved.getStock() == null ? 0 : saved.getStock(), null);
        return ProductResponse.from(saved);
    }

    /** Satıştan kalkan ürün bütün sepetlerden de çıkar. */
    @Transactional
    public void deactivate(Long id) {
        Product product = findActive(id);
        product.setActive(false);
        productRepository.save(product);
        cartItems.fitToStock(product.getId(), 0, null);
    }

    private Product findActive(Long id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ürün bulunamadı."));
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

        if (request.testProduct() != null) {
            product.setTestProduct(request.testProduct());
        }
    }
}
