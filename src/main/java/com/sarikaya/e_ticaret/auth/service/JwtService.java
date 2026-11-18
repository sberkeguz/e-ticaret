package com.sarikaya.e_ticaret.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
public class JwtService {

    // Gizli anahtar artık kodun içinde değil, application.properties'ten okunuyor.
    @Value("${jwt.secret}")
    private String secretKey;

    /** Erişim token'ı kısa ömürlü: süresi dolunca tarayıcı yenileme çereziyle yenisini alır. */
    @Value("${auth.access-token-minutes:15}")
    private long accessTokenMinutes = 15;

    // Token üretir
    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())           // token'ın sahibi
                .issuedAt(new Date(System.currentTimeMillis())) // üretilme zamanı
                .expiration(new Date(System.currentTimeMillis() + accessTokenMinutes * 60_000)) // varsayılan 15 dk
                .signWith(getSignInKey(), Jwts.SIG.HS256)     // HS256 ile imzala
                .compact();
    }

    // Gizli anahtardan imza anahtarı üretir
    private SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // Token'ın içindeki bilgileri okur.
    // İmza yanlışsa veya süre dolduysa hata fırlatır (JwtAuthFilter bunu yakalıyor).
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Token'daki kullanıcı adını verir
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    // Token bu kullanıcıya mı ait ve süresi dolmamış mı?
    public boolean isTokenValid(String token, UserDetails userDetails) {
        Claims claims = extractAllClaims(token);
        return claims.getSubject().equals(userDetails.getUsername())
                && claims.getExpiration().after(new Date());
    }
}