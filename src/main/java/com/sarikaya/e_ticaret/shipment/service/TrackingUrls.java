package com.sarikaya.e_ticaret.shipment.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Takip bağlantısı (Güvenlik S3). Bağlantı yalnız sunucu yapılandırmasındaki sabit şablondan üretilir:
 * {@code shipment.tracking-url-templates.<FIRMA_KODU>=https://.../{trackingNumber}}. Kargo servisinden
 * ya da firmadan gelen ham adres hiçbir zaman aktarılmaz. Takip numarası URL-encode edilerek yerleştirilir.
 * Şablonu olmayan firma (ör. sahte firma FAKE) için bağlantı yoktur (null).
 * Şablon https:// ile başlamıyorsa, host içermiyorsa ya da {trackingNumber} içermiyorsa uygulama açılmaz.
 */
@Component
public class TrackingUrls {
    static final String PREFIX = "shipment.tracking-url-templates";
    private static final String PLACEHOLDER = "{trackingNumber}";

    private final Map<String, String> templates = new HashMap<>();

    @Autowired
    public TrackingUrls(Environment environment) {
        this(read(environment));
    }

    public TrackingUrls(Map<String, String> templatesByCarrierCode) {
        templatesByCarrierCode.forEach((code, template) -> {
            check(code, template);
            templates.put(code.toUpperCase(Locale.ROOT), template);
        });
    }

    static TrackingUrls fromEnvironment(Environment environment) {
        return new TrackingUrls(environment);
    }

    /** Firmanın takip bağlantısı; şablon yoksa ya da takip no boşsa null. */
    public String urlFor(String carrierCode, String trackingNumber) {
        if (carrierCode == null || trackingNumber == null || trackingNumber.isBlank()) {
            return null;
        }
        String template = templates.get(carrierCode.toUpperCase(Locale.ROOT));
        if (template == null) {
            return null;
        }
        return template.replace(PLACEHOLDER, UriUtils.encode(trackingNumber, StandardCharsets.UTF_8));
    }

    private static Map<String, String> read(Environment environment) {
        return Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    private static void check(String code, String template) {
        boolean valid = template != null && template.startsWith("https://") && template.contains(PLACEHOLDER);
        if (valid) {
            try {
                URI uri = new URI(template.replace(PLACEHOLDER, "x"));
                valid = "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
            } catch (URISyntaxException invalid) {
                valid = false;
            }
        }
        if (!valid) {
            throw new IllegalStateException(PREFIX + "." + code
                    + " geçersiz: https:// ile başlamalı, host ve " + PLACEHOLDER + " içermeli.");
        }
    }
}
