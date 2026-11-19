package com.sarikaya.e_ticaret.auth.controller;

import com.sarikaya.e_ticaret.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Yenileme çereziyle çalışan uçlar (yenile, çıkış) CSRF token'ı ister: girişte XSRF-TOKEN çerezi
 * verilir, istek bu değeri X-XSRF-TOKEN başlığında geri göndermezse reddedilir.
 */
@IntegrationTest
class AuthCsrfTest {
    private static final String PASSWORD = "Csrf-test-Password1!";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        username = "csrf_" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshAndLogoutRequireTheCsrfTokenFromLogin() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refresh = login.getResponse().getCookie("refresh_token");
        Cookie csrf = login.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(refresh, "giriş yenileme çerezi vermeli");
        assertNotNull(csrf, "giriş XSRF-TOKEN çerezi vermeli");
        // Tarayıcıya giden gerçek başlıklar: iki çerez de Set-Cookie'de olmalı (biri diğerini ezmemeli).
        var setCookies = login.getResponse().getHeaders("Set-Cookie");
        assertTrue(setCookies.stream().anyMatch(value -> value.startsWith("refresh_token=")), setCookies::toString);
        assertTrue(setCookies.stream().anyMatch(value -> value.startsWith("XSRF-TOKEN=")), setCookies::toString);

        // Başka bir sitenin yapabileceği istek: çerez gider ama token'ı bilemez.
        mvc.perform(post("/api/auth/refresh").cookie(refresh, csrf)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").cookie(refresh, csrf).header("X-XSRF-TOKEN", "yanlis"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(refresh, csrf)).andExpect(status().isForbidden());

        // Ön yüzün isteği: çerezdeki değer başlıkta.
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(refresh, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(header().exists("Authorization"))
                .andReturn();
        Cookie rotated = refreshed.getResponse().getCookie("refresh_token");
        assertNotNull(rotated, "yenileme çerezi yenisiyle değişmeli");

        mvc.perform(post("/api/auth/logout").cookie(rotated, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void endpointsWithoutTheRefreshCookieDoNotNeedACsrfToken() throws Exception {
        // Giriş ve kayıt çerezle kimlik doğrulamaz; CSRF token'ı istenmez (ilk girişte zaten yoktur).
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        // Çerezsiz yenileme: CSRF'e takılmaz, oturum olmadığı için 401.
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }
}
