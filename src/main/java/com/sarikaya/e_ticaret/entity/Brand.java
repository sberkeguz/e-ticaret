package com.sarikaya.e_ticaret.entity;

import jakarta.persistence.*;
import lombok.Data;


@Entity
@Table(name = "brands")
@Data

public class Brand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;
}
