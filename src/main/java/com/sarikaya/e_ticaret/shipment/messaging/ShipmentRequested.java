package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * "Kargoya ver" komutu (v1), cargo-service'in Messaging/Contracts/ShipmentRequested.cs sözleşmesi.
 * JSON camelCase; occurredAt ISO-8601 metin (milisaniyeye yuvarlanır); declaredValue JSON sayısı.
 * cargo-service'in Validate kuralları: messageId ≤100, type "ShipmentRequested", version ≥1,
 * requestNo ≥1, idempotencyKey tam "order-{orderId}", paymentStatus dolu (yalnız "PAID" kargoya verilir),
 * alıcının 7 alanı dolu (.NET string.IsNullOrWhiteSpace anlamında: {@link SafeText#isNullOrWhiteSpace}) ve
 * {@link SafeText}'e uygun, itemCount ≥1, declaredValue ≥0.
 * recipient kişisel veridir: outbox satırından yayın onayından sonra silinir, loga girmez.
 */
public record ShipmentRequested(String messageId, String type, int version, Instant occurredAt, String traceId,
                                long orderId, int requestNo, String idempotencyKey, String paymentStatus,
                                Recipient recipient, Parcel parcel) {
    public static final String MESSAGE_TYPE = "ShipmentRequested";
    public static final String PAID = "PAID";

    /** Ödemesi PAID olan sipariş için komut. Alıcı ve koli siparişten alınır. */
    public static ShipmentRequested forOrder(UUID messageId, Order order, int requestNo, String traceId, Instant now) {
        return new ShipmentRequested(messageId.toString(), MESSAGE_TYPE, 1, now.truncatedTo(ChronoUnit.MILLIS), traceId,
                order.getId(), requestNo, "order-" + order.getId(), PAID, Recipient.from(order),
                Parcel.of(order.getItems().stream().mapToInt(OrderItem::getQuantity), order.getTotal()));
    }

    /**
     * cargo-service'in bu komutu reddetme nedeni; kabul edecekse null. "Kargoya ver" ön kontrolü ve
     * yöneticinin canShip'i aynı kuralı buradan kullanır.
     */
    public Rejection rejection() {
        return rejectionOf(recipient, parcel);
    }

    /**
     * Yönetici listesindeki sipariş (OrderResponse) için aynı kural; komut kurulmadan, ek sorgu olmadan
     * sipariş alanlarından hesaplanır.
     */
    public static Rejection rejectionOf(OrderResponse order) {
        Recipient recipient = new Recipient(order.firstName(), order.lastName(), order.phone(), order.city(),
                order.district(), order.neighborhood(), order.address());
        List<OrderResponse.Item> items = order.items() == null ? List.of() : order.items();
        return rejectionOf(recipient, Parcel.of(items.stream().mapToInt(OrderResponse.Item::quantity), order.total()));
    }

    /** Öncelik: alıcıda geçersiz karakter, sonra eksik alıcı alanı, sonra geçersiz koli. */
    private static Rejection rejectionOf(Recipient recipient, Parcel parcel) {
        RecipientProblem problem = recipient.problem();
        if (problem == RecipientProblem.UNSAFE_CHARACTER) {
            return Rejection.UNSAFE_RECIPIENT;
        }
        if (problem == RecipientProblem.MISSING) {
            return Rejection.MISSING_RECIPIENT;
        }
        return parcel.valid() ? null : Rejection.INVALID_PARCEL;
    }

    /** cargo-service'in komutu reddetme nedeni (alan değeri söylenmez). */
    public enum Rejection { UNSAFE_RECIPIENT, MISSING_RECIPIENT, INVALID_PARCEL }

    /** Alıcı bilgisi. toString bilerek "[gizli]": nesne yanlışlıkla loga verilse de kişisel veri yazılmaz. */
    public record Recipient(String firstName, String lastName, String phone, String city, String district,
                            String neighborhood, String address) {
        public static Recipient from(Order order) {
            return new Recipient(order.getFirstName(), order.getLastName(), order.getPhone(), order.getCity(),
                    order.getDistrict(), order.getNeighborhood(), order.getAddress());
        }

        /** cargo-service'in reddedeceği alan varsa sorunun türü (değer söylenmez); yoksa null. */
        public RecipientProblem problem() {
            boolean missing = false;
            for (String value : new String[]{firstName, lastName, phone, city, district, neighborhood, address}) {
                if (SafeText.isNullOrWhiteSpace(value)) {
                    missing = true;
                } else if (!SafeText.isSafe(value)) {
                    return RecipientProblem.UNSAFE_CHARACTER;
                }
            }
            return missing ? RecipientProblem.MISSING : null;
        }

        @Override
        public String toString() {
            return "[gizli]";
        }
    }

    public enum RecipientProblem { MISSING, UNSAFE_CHARACTER }

    /** Koli: ürün adedi ve beyan edilen değer (sipariş tutarı, TL). */
    public record Parcel(int itemCount, BigDecimal declaredValue) {
        /** itemCount kalem adetlerinin toplamıdır. */
        public static Parcel of(IntStream quantities, BigDecimal declaredValue) {
            return new Parcel(quantities.sum(), declaredValue);
        }

        public boolean valid() {
            return itemCount >= 1 && declaredValue != null && declaredValue.signum() >= 0;
        }
    }

    /** Alıcı alanları "[gizli]" yazılır. */
    @Override
    public String toString() {
        return "ShipmentRequested[messageId=" + messageId + ", orderId=" + orderId + ", requestNo=" + requestNo + "]";
    }
}
