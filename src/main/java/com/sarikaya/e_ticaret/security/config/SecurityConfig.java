package com.sarikaya.e_ticaret.security.config;

import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.filter.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    // Her istekte token'ı kontrol eden filtremiz
    private final JwtAuthFilter jwtAuthFilter;

    // Login sırasında kullanıcıyı veritabanından bulan servis
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Kullanıcı adı bulunamadı."));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS ayarlarını aşağıdaki corsConfigurationSource bean'inden okur
                .cors(Customizer.withDefaults())
                // Token (JWT) kullandığımız ve session tutmadığımız için CSRF kapalı
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Login, register ve hata sayfası herkese açık
                        .requestMatchers("/api/auth/**", "/api/user/**", "/error").permitAll()
                        // Admin endpoint'leri sadece ADMIN rolüne açık
                        // (User.getAuthorities içinde "ROLE_" + role.name() olmalı)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Geri kalan her şey için giriş yapmış olmak yeterli
                        .anyRequest().authenticated()
                )
                // Session tutma, her istek token ile doğrulanır
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bizim JWT filtremiz, Spring'in kendi login filtresinden ÖNCE çalışsın
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Sadece React'in çalıştığı porta izin veriyoruz
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        // İzin verilen HTTP metotları
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // React'in gönderebileceği header'lar
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // ÖNEMLİ: Tarayıcının JS'e "Authorization" response header'ını göstermesine izin verir.
        // Bunu yazmazsak React res.headers.get("Authorization") ile token'ı okuyamaz.
        configuration.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}