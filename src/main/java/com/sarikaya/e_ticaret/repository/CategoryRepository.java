package com.sarikaya.e_ticaret.repository;

import com.sarikaya.e_ticaret.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    //JpaRepository'nin varsayılan metotları save findAll findById gibigibi
}
