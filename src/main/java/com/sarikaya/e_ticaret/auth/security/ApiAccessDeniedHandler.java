package com.sarikaya.e_ticaret.auth.security;

import com.sarikaya.e_ticaret.exception.ApiError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Giriş yapmış ama yetkisi olmayan kullanıcının 403'ü (ör. müşteri /api/admin/** çağırdı): projenin
 * ApiError gövdesiyle, code ACCESS_DENIED. Ön yüz kodlu 403'te kullanıcıyı /login'e atmaz.
 * CSRF reddi eskisi gibi Spring'in varsayılan işleyicisine bırakılır (kodsuz 403). Girişsiz istek bu
 * sınıfa hiç gelmez; authenticationEntryPoint'e gider (değişmedi, kodsuz 403).
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {
    private final AccessDeniedHandler csrfDefault = new AccessDeniedHandlerImpl();
    private final ObjectMapper json;

    public ApiAccessDeniedHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        if (denied instanceof CsrfException) {
            csrfDefault.handle(request, response, denied);
            return;
        }
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ApiError(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "Bu işlem için yetkiniz yok.", Instant.now()));
    }
}
