package com.sarikaya.e_ticaret.product.service;

import com.sarikaya.e_ticaret.brand.repository.BrandRepository;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.category.repository.CategoryRepository;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.product.dto.ProductRequest;
import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceTestProductTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final ProductService service = new ProductService(productRepository, categoryRepository, brandRepository, cartItemRepository);

    @Test
    void customersGetListWithoutTestProducts() {
        when(productRepository.findAllByActiveTrueAndTestProductFalse()).thenReturn(List.of(product(1L, false)));

        List<ProductResponse> result = service.listActive(false);

        assertEquals(1, result.size());
        verify(productRepository, never()).findAllByActiveTrue();
    }

    @Test
    void adminsGetListWithTestProducts() {
        when(productRepository.findAllByActiveTrue()).thenReturn(List.of(product(1L, false), product(2L, true)));

        List<ProductResponse> result = service.listActive(true);

        assertEquals(2, result.size());
        assertTrue(result.get(1).testProduct());
    }

    @Test
    void customerCannotOpenTestProductDetail() {
        when(productRepository.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(product(2L, true)));

        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                () -> service.getActive(2L, false));

        assertEquals("Ürün bulunamadı.", error.getMessage());
    }

    @Test
    void adminCanOpenTestProductDetail() {
        when(productRepository.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(product(2L, true)));

        assertTrue(service.getActive(2L, true).testProduct());
    }

    @Test
    void missingProductGivesNotFound() {
        when(productRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getActive(99L, true));
    }

    @Test
    void updateWithoutTestFlagKeepsTheCurrentValue() {
        Product existing = product(2L, true);
        when(productRepository.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(2L, request(null));

        assertTrue(existing.isTestProduct());
    }

    @Test
    void updateCanRemoveTestFlag() {
        Product existing = product(2L, true);
        when(productRepository.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(2L, request(false));

        assertFalse(existing.isTestProduct());
    }

    private Product product(Long id, boolean testProduct) {
        Product product = new Product();
        product.setId(id);
        product.setName("Ürün " + id);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(5);
        product.setTestProduct(testProduct);
        return product;
    }

    private ProductRequest request(Boolean testProduct) {
        return new ProductRequest("Ürün", null, new BigDecimal("10.00"), 5, null, null, null, List.of(), testProduct);
    }

    @Test
    void loweringStockFitsCartsToNewStock() {
        Product product = new Product();
        product.setId(5L);
        product.setActive(true);
        when(productRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(5L, new ProductRequest("Elma", null, new BigDecimal("5.00"), 0, null, null, null, List.of(), null));

        verify(cartItemRepository).fitToStock(5L, 0, null);
    }

    @Test
    void deactivatedProductLeavesAllCarts() {
        Product product = new Product();
        product.setId(5L);
        product.setActive(true);
        when(productRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(product));

        service.deactivate(5L);

        verify(cartItemRepository).fitToStock(5L, 0, null);
    }
}
