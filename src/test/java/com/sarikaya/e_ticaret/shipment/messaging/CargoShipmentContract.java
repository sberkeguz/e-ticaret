package com.sarikaya.e_ticaret.shipment.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * cargo-service'in ShipmentRequested.Validate() ve SafeText.IsSafe kurallarının test için Java karşılığı
 * (services/cargo-service/src/CargoService/Messaging/Contracts/ShipmentRequested.cs, Recipient.cs,
 * SafeText.cs). Ek olarak cargo-service Tester'ının sabitlediği örnek komut ({@link #SAMPLE}) alan adı ve
 * JSON tipi karşılaştırması için tutulur.
 */
public final class CargoShipmentContract {
    /** cargo-service tarafında kabul edilen örnek komut (Direktör, 2026-11-26). */
    public static final String SAMPLE = """
            {"messageId":"3f1c2a9e-8b7d-4c1e-9a55-0d2f6b7e4a10","type":"ShipmentRequested","version":1,\
            "occurredAt":"2026-11-26T10:15:30.123Z","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","orderId":123456,\
            "requestNo":1,"idempotencyKey":"order-123456","paymentStatus":"PAID","recipient":{"firstName":"Çağrı",\
            "lastName":"Öztürk","phone":"05551234567","city":"İstanbul","district":"Kadıköy","neighborhood":"Moda",\
            "address":"Şair Nefi Sk. No:5 D:3"},"parcel":{"itemCount":2,"declaredValue":149.90}}""";

    private static final String[] RECIPIENT_FIELDS =
            {"firstName", "lastName", "phone", "city", "district", "neighborhood", "address"};

    private CargoShipmentContract() {
    }

    /** Geçersiz alan adları (boşsa cargo-service komutu kabul eder). */
    public static List<String> validate(JsonNode command) {
        List<String> errors = new ArrayList<>();
        String messageId = text(command, "messageId");
        if (isNullOrWhiteSpace(messageId) || messageId.length() > 100 || !isSafe(messageId)) {
            errors.add("messageId");
        }
        JsonNode traceId = command.get("traceId");
        if (traceId != null && !traceId.isNull() && (!traceId.isString() || !isSafe(traceId.asString()))) {
            errors.add("traceId");
        }
        if (!"ShipmentRequested".equals(text(command, "type"))) {
            errors.add("type");
        }
        if (!isInt(command, "version") || command.get("version").asInt() < 1) {
            errors.add("version");
        }
        String occurredAt = text(command, "occurredAt");
        try {
            OffsetDateTime.parse(occurredAt);
        } catch (DateTimeParseException | NullPointerException invalid) {
            errors.add("occurredAt");
        }
        long orderId = isInt(command, "orderId") ? command.get("orderId").asLong() : 0;
        if (orderId <= 0) {
            errors.add("orderId");
        }
        if (!isInt(command, "requestNo") || command.get("requestNo").asInt() < 1) {
            errors.add("requestNo");
        }
        if (!("order-" + orderId).equals(text(command, "idempotencyKey"))) {
            errors.add("idempotencyKey");
        }
        String paymentStatus = text(command, "paymentStatus");
        if (isNullOrWhiteSpace(paymentStatus)) {
            errors.add("paymentStatus");
        }
        JsonNode recipient = command.get("recipient");
        if (recipient == null || !recipient.isObject()) {
            errors.add("recipient");
        } else {
            for (String field : RECIPIENT_FIELDS) {
                String value = text(recipient, field);
                if (isNullOrWhiteSpace(value) || !isSafe(value)) {
                    errors.add("recipient." + field);
                }
            }
        }
        JsonNode parcel = command.get("parcel");
        if (parcel == null || !parcel.isObject()) {
            errors.add("parcel");
        } else {
            if (!isInt(parcel, "itemCount") || parcel.get("itemCount").asInt() < 1) {
                errors.add("parcel.itemCount");
            }
            JsonNode value = parcel.get("declaredValue");
            if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) {
                errors.add("parcel.declaredValue");
            }
        }
        return errors;
    }

    /** Yol → JSON tipi (ör. "parcel.declaredValue" → NUMBER). Tam/ondalık sayı ayrımı yapılmaz. */
    public static Map<String, JsonNodeType> shape(JsonNode node) {
        Map<String, JsonNodeType> shape = new TreeMap<>();
        collect("", node, shape);
        return shape;
    }

    private static void collect(String prefix, JsonNode node, Map<String, JsonNodeType> shape) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = prefix + field.getKey();
            if (field.getValue().isObject()) {
                collect(path + ".", field.getValue(), shape);
            } else {
                shape.put(path, field.getValue().getNodeType());
            }
        }
    }

    /**
     * .NET char.IsWhiteSpace'in true döndüğü karakterlerin tam listesi (belgelenen liste, üretim kodundan
     * bağımsız yazıldı): Zs, Zl (U+2028), Zp (U+2029), U+0009–U+000D, U+0085.
     */
    public static final String DOTNET_WHITESPACE = "          "
            + "      　  \u0009\n\u000B\u000C\r\u0085";

    /** .NET string.IsNullOrWhiteSpace: null, boş ya da yalnız {@link #DOTNET_WHITESPACE} karakterleri. */
    public static boolean isNullOrWhiteSpace(String text) {
        return text == null || text.chars().allMatch(c -> DOTNET_WHITESPACE.indexOf(c) >= 0);
    }

    /** SafeText.IsSafe: C0 (\t \n \r hariç), DEL, C1, eşsiz vekil, U+FFFD, U+FFFE, U+FFFF yasak. */
    public static boolean isSafe(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return false;
            }
            if (Character.isLowSurrogate(c) || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')
                    || (c >= 0x7F && c <= 0x9F) || c == '�' || c == '￾' || c == '￿') {
                return false;
            }
        }
        return true;
    }

    private static boolean isInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
