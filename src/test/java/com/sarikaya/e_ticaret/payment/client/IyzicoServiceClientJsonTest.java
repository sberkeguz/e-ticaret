package com.sarikaya.e_ticaret.payment.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tutarlar IyzicoService'e metin olarak gider ("25.00"); karşı tarafta float'a dönüşmez. */
class IyzicoServiceClientJsonTest {
    @Test
    void amountsAreSentAsDecimalText() {
        var request = new IyzicoServiceClient.StartPayment(42L, new BigDecimal("25.00"), false, "127.0.0.1",
                null, null, List.of(new IyzicoServiceClient.BasketItem("4", "Armut", new BigDecimal("12.50"))), null);

        String json = JsonMapper.builder().build().writeValueAsString(request);

        assertTrue(json.contains("\"amount\":\"25.00\""), json);
        assertTrue(json.contains("\"price\":\"12.50\""), json);
    }
}
