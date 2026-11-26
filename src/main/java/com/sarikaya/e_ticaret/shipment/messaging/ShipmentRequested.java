package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
        int itemCount = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return new ShipmentRequested(messageId.toString(), MESSAGE_TYPE, 1, now.truncatedTo(ChronoUnit.MILLIS), traceId,
                order.getId(), requestNo, "order-" + order.getId(), PAID, Recipient.from(order),
                new Parcel(itemCount, order.getTotal()));
    }

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
