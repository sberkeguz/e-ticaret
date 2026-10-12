package com.sarikaya.e_ticaret.catalog.repository;

import com.sarikaya.e_ticaret.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
List<Category> findByParentIsNull();

List<Category> findByParentId(Long parentId);

Category findByName(String name);
}