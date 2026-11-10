package com.sarikaya.e_ticaret.product.controller;

import com.sarikaya.e_ticaret.product.dto.ProductResponse;
import com.sarikaya.e_ticaret.product.service.ProductService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

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