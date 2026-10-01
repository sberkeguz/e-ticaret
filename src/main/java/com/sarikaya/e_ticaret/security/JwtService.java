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
        return Jwts.builder() // 1. Token oluşturma sürecini başlatır.
                .setClaims(new HashMap<>()) // 2. Biletin içine eklenecek ekstra bilgiler (şu an boş bıraktık).
                .setSubject(userDetails.getUsername()) // 3. Bu bilet kimin adına kesildi? (Kullanıcı adını yazar).
                .setIssuedAt(new Date(System.currentTimeMillis())) // 4. Bilet şu an, tam bu saniyede kesildi diyoruz.
                .setExpiration(new Date(System.currentTimeMillis()+1000*60*24)) // 5. Biletin son kullanma tarihini belirliyoruz (Şu anki formül 24 dakika sonrasını gösteriyor).
                .signWith(getSignInKey(), SignatureAlgorithm.HS256) // 6. En önemli kısım: Gizli anahtarımızı (mührümüzü) ve HS256 şifreleme algoritmasını kullanarak bileti imzalıyoruz. Değiştirilirse anlarız.
                .compact(); // 7. Tüm bu ayarları paketleyip "eyJh..." diye başlayan tek bir uzun metin (String) haline getirir ve dışarı verir.
    }
    private Key getSignInKey(){
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);

    }
}
