package com.sarikaya.e_ticaret.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;

@Service
public class JwtService {

    // Gizli anahtar artık kodun içinde değil, application.properties'ten okunuyor.
    @Value("${jwt.secret}")
    private String secretKey;

    // Token üretir
    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
                .setClaims(new HashMap<>())                      // ek bilgi (şimdilik boş)
                .setSubject(userDetails.getUsername())           // token'ın sahibi
                .setIssuedAt(new Date(System.currentTimeMillis())) // üretilme zamanı
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24)) // 24 saat
                .signWith(getSignInKey(), SignatureAlgorithm.HS256) // HS256 ile imzala
                .compact();
    }

    // Gizli anahtardan imza anahtarı üretir
    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // Token'ın içindeki bilgileri (claims) okur.
    // İmza yanlışsa veya süre dolduysa hata fırlatır (JwtAuthFilter bunu yakalıyor).
    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
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