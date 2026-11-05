<<<<<<<< HEAD:src/main/java/com/sarikaya/e_ticaret/audit/entity/LoginHistory.java
package com.sarikaya.e_ticaret.audit.entity;
========
package com.sarikaya.e_ticaret.user.entity;
>>>>>>>> origin/master:src/main/java/com/sarikaya/e_ticaret/user/entity/LoginHistory.java

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "login_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime loggedInAt;
}