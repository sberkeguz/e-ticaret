package com.sarikaya.e_ticaret.payment.entity;

import com.sarikaya.e_ticaret.order.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    /** iyzico ödeme numarası. */
    @Column(unique = true)
    private String token;

    /** IyzicoService'teki son ödeme denemesinin numarası (iyzico'ya conversationId olarak gider). */
    @Column(unique = true)
    private UUID attemptId;

    @Column(nullable = false, length = 30)
    private String status = "PENDING";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
