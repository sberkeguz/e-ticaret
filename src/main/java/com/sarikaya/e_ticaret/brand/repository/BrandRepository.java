package com.sarikaya.e_ticaret.brand.repository;

import com.sarikaya.e_ticaret.brand.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {
}