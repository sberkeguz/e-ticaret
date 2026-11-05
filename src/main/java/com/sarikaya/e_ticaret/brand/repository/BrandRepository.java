<<<<<<<< HEAD:src/main/java/com/sarikaya/e_ticaret/brand/repository/BrandRepository.java
package com.sarikaya.e_ticaret.brand.repository;

import com.sarikaya.e_ticaret.brand.entity.Brand;
========
package com.sarikaya.e_ticaret.catalog.repository;

import com.sarikaya.e_ticaret.catalog.entity.Brand;
>>>>>>>> origin/master:src/main/java/com/sarikaya/e_ticaret/catalog/repository/BrandRepository.java
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {
}