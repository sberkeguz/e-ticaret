package com.sarikaya.e_ticaret.security;


import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;

@Service
public class JwtService {
    private static final String SECRET_KEY = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    public String generateToken(UserDetails userDetails){
        return Jwts.builder() //token oluşturma
                .setClaims(new HashMap<>()) // ek bilgi yaz
                .setSubject(userDetails.getUsername()) // kullanıcı adı
                .setIssuedAt(new Date(System.currentTimeMillis())) // token kesildiği sanbiye
                .setExpiration(new Date(System.currentTimeMillis()+1000*60*24)) // son kullanma tarihi/24dk şu an
                .signWith(getSignInKey(), SignatureAlgorithm.HS256) // HS256 şifreleme
                .compact();
    }
    private Key getSignInKey(){
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);

    }
}
