package com.sarikaya.e_ticaret.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * logback-spring.xml'deki OTEL ekleyicisini Spring'in OpenTelemetry nesnesine bağlar. Bağlanmadan
 * önce (açılışın ilk saniyeleri) yazılan loglar yalnızca konsola gider. Gönderim kapalıysa
 * (OTEL_ENABLED=false, testler) OpenTelemetry nesnesi hiçbir yere göndermez.
 */
@Component
class OpenTelemetryLogging implements InitializingBean {
    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogging(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
