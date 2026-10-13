package com.sarikaya.e_ticaret.catalog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

@ManyToMany
@JoinTable(
        name = "category_parents",
        joinColumns = @JoinColumn(name = "category_id"),
        inverseJoinColumns =  @JoinColumn(name = "parent_id")
)
    private Set<Category> parents = new LinkedHashSet<>();

@ManyToMany(mappedBy = "parents")
    private Set<Category> children = new LinkedHashSet<>();
}