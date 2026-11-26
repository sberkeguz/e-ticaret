package com.sarikaya.e_ticaret.auth.security;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S5: giriş yapmış ama yetkisi olmayan kullanıcıya 403 + ApiError (code ACCESS_DENIED) döner; ön yüz bu
 * kodla /login'e atmaz. Girişsiz istek (anonim) ve CSRF reddi eskisi gibi kodsuz 403'tür.
 */
@IntegrationTest
class ApiAccessDeniedHandlerTest {
    private static final String PASSWORD = "Denied-test-Password1!";

    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void customerOnAdminEndpointGetsAccessDeniedCode() throws Exception {
        User customer = User.builder().username("denied-" + UUID.randomUUID()).password("x").role(Role.USER).build();

        mvc.perform(get("/api/admin/orders").with(user(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Bu işlem için yetkiniz yok."));
        mvc.perform(post("/api/admin/orders/1/shipment").with(user(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anonymousRequestStaysCodelessForbidden() throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/orders"))
                .andExpect(status().isForbidden())
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("ACCESS_DENIED"));
    }

    @Test
    void csrfRejectionIsUnchanged() throws Exception {
        String username = "denied_" + UUID.randomUUID().toString().substring(0, 8);
        String credentials = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk());
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refresh = login.getResponse().getCookie("refresh_token");
        Cookie csrf = login.getResponse().getCookie("XSRF-TOKEN");

        MvcResult rejected = mvc.perform(post("/api/auth/refresh").cookie(refresh, csrf))
                .andExpect(status().isForbidden())
                .andReturn();

        assertFalse(rejected.getResponse().getContentAsString().contains("ACCESS_DENIED"));
    }
}
