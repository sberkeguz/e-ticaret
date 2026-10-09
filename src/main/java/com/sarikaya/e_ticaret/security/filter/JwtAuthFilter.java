package com.sarikaya.e_ticaret.security.filter;

import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// OncePerRequestFilter: Her istek için filtrenin sadece bir kez çalışmasını garanti eder.
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    // UserDetailsService yerine doğrudan repository kullanıyoruz
    // Yoksa SecurityConfig <-> JwtAuthFilter arasında döngüsel bağımlılık oluşur
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // Token yoksa dokunma istek devam etsin
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7); // "Bearer " kısmını at

        try {
            String username = jwtService.extractUsername(token);

            // Kullanıcı adı varsa ve henüz giriş yapılmış sayılmıyorsa
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                var user = userRepository.findByUsername(username).orElse(null);

                // Kullanıcı var, token geçerli ve hesap aktifse kimliği tanıt
                if (user != null && user.isEnabled() && jwtService.isTokenValid(token, user)) {
                    var authToken = new UsernamePasswordAuthenticationToken(
                            user, null, user.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Süresi dolmuş / bozuk token: kimlik tanıtma, istek 403 ile reddedilir
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}