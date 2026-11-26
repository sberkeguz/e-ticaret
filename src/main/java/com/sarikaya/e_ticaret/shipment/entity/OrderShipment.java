package com.sarikaya.e_ticaret.shipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Siparişin kargo gönderisi (sipariş başına en çok bir satır, V21). Alıcı bilgisi ve kargo firmasının
 * ham hata metni burada tutulmaz; hata yalnızca sabit kodla (failureCode) saklanır.
 */
@Entity
@Table(name = "order_shipments")
@Getter @Setter @NoArgsConstructor
public class OrderShipment {
    /** cargo-service ödemeyi onaylı görmedi (olayda status=REJECTED). */
    public static final String PAYMENT_NOT_CONFIRMED = "PAYMENT_NOT_CONFIRMED";
    /** Kargo firması gönderiyi kesin olarak reddetti (olayda status=FAILED). */
    public static final String CARRIER_FAILED = "CARRIER_FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;

    /** Kaçıncı kargo isteği (1'den başlar). Olaydaki requestNo bununla eşleşmelidir. */
    @Column(nullable = false)
    private int requestNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status;

    /** cargo-service'teki gönderinin numarası; ilk olayla gelir. */
    private UUID cargoShipmentId;

    @Column(length = 50)
    private String carrierCode;

    @Column(length = 100)
    private String carrierName;

    @Column(length = 100)
    private String trackingNumber;

    /** Yalnız FAILED'da dolu: {@link #CARRIER_FAILED} ya da {@link #PAYMENT_NOT_CONFIRMED}. */
    @Column(length = 40)
    private String failureCode;

    /** Durumun değiştiği an (olaydaki statusAt; istek anında şimdiki zaman). */
    @Column(nullable = false)
    private Instant statusAt;

    /** Uygulanan son olayın sırası. Tekrar denemede sıfırlanmaz (cargo-service'te sıra gönderi başına artar). */
    @Column(nullable = false)
    private int lastEventSequence;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
