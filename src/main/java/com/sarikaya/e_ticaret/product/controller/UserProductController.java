package com.sarikaya.e_ticaret.product.controller;

import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.dto.ProductPageResponse;
import com.sarikaya.e_ticaret.product.service.ProductSearch;
import com.sarikaya.e_ticaret.product.service.ProductService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class UserProductController {

    private final ProductService productService;

    public UserProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> listProducts(Authentication auth) {
        return productService.listActive(isAdmin(auth));
    }

    /**
     * Mağaza listesi, sayfa sayfa ve filtreli. categoryIds/brandIds virgülle ayrılır:
     * /api/products/search?page=0&size=24&q=elma&categoryIds=3,4&minPrice=10&inStock=true
     */
    @GetMapping("/search")
    public ProductPageResponse search(Authentication auth,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "24") int size,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(required = false) List<Long> categoryIds,
                                      @RequestParam(required = false) BigDecimal minPrice,
                                      @RequestParam(required = false) BigDecimal maxPrice,
                                      @RequestParam(defaultValue = "false") boolean inStock,
                                      @RequestParam(required = false) List<Long> brandIds) {
        return productService.search(
                new ProductSearch(q, categoryIds, minPrice, maxPrice, inStock, brandIds, isAdmin(auth)), page, size);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id, Authentication auth) {
        return productService.getActive(id, isAdmin(auth));
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) {
            return false;
        }

        for (GrantedAuthority authority : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}