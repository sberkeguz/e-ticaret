<<<<<<<< HEAD:src/main/java/com/sarikaya/e_ticaret/brand/entity/Brand.java
package com.sarikaya.e_ticaret.brand.entity;
========
package com.sarikaya.e_ticaret.catalog.entity;
>>>>>>>> origin/master:src/main/java/com/sarikaya/e_ticaret/catalog/entity/Brand.java

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
