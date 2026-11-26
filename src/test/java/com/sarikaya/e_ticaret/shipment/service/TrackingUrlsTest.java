package com.sarikaya.e_ticaret.shipment.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** S3: takip bağlantısı yalnız sunucudaki https şablonundan; takip no URL-encode edilir. */
class TrackingUrlsTest {
    private final TrackingUrls urls = new TrackingUrls(Map.of(
            "YURTICI", "https://kargo.example.com/takip?no={trackingNumber}",
            "ARAS", "https://aras.example.com/t/{trackingNumber}"));

    @Test
    void knownCarrierGetsHttpsUrlWithEncodedTrackingNumber() {
        assertEquals("https://kargo.example.com/takip?no=ABC123", urls.urlFor("YURTICI", "ABC123"));
        assertEquals("https://aras.example.com/t/AB%2012%2F%3F%26x%3D%3Cy%3E", urls.urlFor("ARAS", "AB 12/?&x=<y>"));
    }

    @Test
    void carrierWithoutTemplateHasNoUrl() {
        assertNull(urls.urlFor("FAKE", "FAKE-1"));
        assertNull(urls.urlFor(null, "X"));
    }

    @Test
    void missingTrackingNumberHasNoUrl() {
        assertNull(urls.urlFor("YURTICI", null));
        assertNull(urls.urlFor("YURTICI", " "));
    }

    @Test
    void carrierCodeLookupIgnoresCase() {
        assertEquals("https://kargo.example.com/takip?no=A1", urls.urlFor("yurtici", "A1"));
    }

    @Test
    void nonHttpsOrPlaceholderlessTemplateFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new TrackingUrls(Map.of("X", "http://kargo.example.com/{trackingNumber}")));
        assertThrows(IllegalStateException.class,
                () -> new TrackingUrls(Map.of("X", "javascript:alert({trackingNumber})")));
        assertThrows(IllegalStateException.class,
                () -> new TrackingUrls(Map.of("X", "https://kargo.example.com/takip")));
        assertThrows(IllegalStateException.class,
                () -> new TrackingUrls(Map.of("X", "https:///{trackingNumber}")));
    }

    @Test
    void templatesAreReadFromConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("shipment.tracking-url-templates.YURTICI", "https://kargo.example.com/takip?no={trackingNumber}");

        TrackingUrls fromConfig = TrackingUrls.fromEnvironment(environment);

        assertEquals("https://kargo.example.com/takip?no=Z9", fromConfig.urlFor("YURTICI", "Z9"));
        assertNull(TrackingUrls.fromEnvironment(new MockEnvironment()).urlFor("YURTICI", "Z9"));
    }
}
