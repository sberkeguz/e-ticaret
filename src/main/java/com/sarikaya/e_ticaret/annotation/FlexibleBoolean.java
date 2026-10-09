package com.sarikaya.e_ticaret.annotation;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)

// @Target => nerelerde çalışacağına karar verir
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})

// @JacksonAnnotationsInside=> bundle
@JacksonAnnotationsInside

// @JsonDeserialize =>FlexibleBooleanDeserializer sınıfındaki kuralların uygulanmasını söyler
@JsonDeserialize(using = FlexibleBooleanDeserializer.class)

// public @interface FlexibleBoolean: Kendi anotasyonumuzun tanımlanır
public @interface FlexibleBoolean {
}