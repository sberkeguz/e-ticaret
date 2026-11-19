package com.sarikaya.e_ticaret.auth.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import com.sarikaya.e_ticaret.auth.service.RefreshTokenService;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    // Her istekte token'ı kontrol eden filtremiz
    private final JwtAuthFilter jwtAuthFilter;

    @Value("${auth.refresh-token-days:14}")
    private long refreshTokenDays;

    @Value("${auth.refresh-cookie-secure:true}")
    private boolean secureCookies;

    /** Yenileme çereziyle kimlik doğrulayan uçlar: CSRF denetimi yalnızca bunlarda yapılır. */
    private static final Set<String> COOKIE_AUTHENTICATED_PATHS = Set.of("/api/auth/refresh", "/api/auth/logout");
    /** 3D Secure ve Ödeme Formu dönüşleri: banka/iyzico sayfasından gelen form gönderimi. */
    private static final Set<String> PAYMENT_RETURN_PATHS = Set.of("/api/payments/callback", "/api/payments/form-callback");

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
                // CSRF yalnızca tarayıcının kendiliğinden gönderdiği kimlik bilgisiyle (yenileme çerezi)
                // çalışan isteklerde anlamlıdır; diğer uçlar Authorization başlığıyla doğrulanır ve başka
                // bir site bu başlığı koyamaz. Çerezle gelen istek, XSRF-TOKEN çerezindeki değeri
                // X-XSRF-TOKEN başlığında geri göndermek zorunda (başka site bu çerezi okuyamaz).
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(SecurityConfig::sentWithRefreshCookie))
                // 3D Secure dönüş sayfası, ödeme ekranındaki iframe içinde açılır; yalnızca ona
                // çerçeve izni veriyoruz, diğer tüm yanıtlar X-Frame-Options: DENY ile kalır.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                notPaymentCallback(),
                                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY))))
                .authorizeHttpRequests(auth -> auth
                        // Bekletmeli cevabın (ör. ödeme sonucu) ikinci geçişi: isteğin kendisi ilk geçişte
                        // zaten doğrulandı; JWT filtresi bu geçişte çalışmadığı için burada tekrar sorulmaz.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Sağlık kontrolü (Docker bunu sorar). Yalnızca UP/DOWN döner, ayrıntı vermez.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API dokümanı (Swagger UI). Yalnızca doküman; uçların kendisi yine yetki ister.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Login, register ve hata sayfası herkese açık
                        .requestMatchers("/api/auth/**", "/api/user/**", "/api/payments/callback", "/api/payments/form-callback", "/error").permitAll()
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

    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        // Ön yüz değeri okuyup başlığa koyabilsin diye HttpOnly değil; SameSite=Strict ile başka siteye gitmez.
        // Yenileme çereziyle aynı ömürde: tarayıcı kapanıp açılınca da oturum yenilenebilsin.
        repository.setCookieCustomizer(cookie -> cookie
                .httpOnly(false)
                .secure(secureCookies)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofDays(refreshTokenDays)));
        return repository;
    }

    private static boolean sentWithRefreshCookie(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !COOKIE_AUTHENTICATED_PATHS.contains(request.getRequestURI())) {
            return false;
        }
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies)
                .anyMatch(cookie -> RefreshTokenService.COOKIE_NAME.equals(cookie.getName()));
    }

    private static RequestMatcher notPaymentCallback() {
        return request -> !"/api/payments/callback".equals(request.getRequestURI());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Sadece React'in çalıştığı porta izin veriyoruz
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        // İzin verilen HTTP metotları
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // React'in gönderebileceği header'lar
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // ÖNEMLİ: Tarayıcının JS'e "Authorization" response header'ını göstermesine izin verir.
        // Bunu yazmazsak React res.headers.get("Authorization") ile token'ı okuyamaz.
        configuration.setExposedHeaders(List.of("Authorization"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        // 3D Secure / Ödeme Formu dönüşü, bankanın veya iyzico'nun sayfasından tarayıcının yaptığı bir
        // form gönderimidir (sayfa geçişi); CORS'a tabi değildir. Bu uçlar için CORS kuralı hiç
        // uygulanmaz: yabancı Origin başlığı yüzünden reddedilmezler, ama başka bir sitenin JavaScript'ine
        // de izin (Access-Control-Allow-Origin) verilmez. Sonuç iyzico'dan imzalı olarak doğrulanır.
        return request -> PAYMENT_RETURN_PATHS.contains(request.getRequestURI())
                ? null
                : source.getCorsConfiguration(request);
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