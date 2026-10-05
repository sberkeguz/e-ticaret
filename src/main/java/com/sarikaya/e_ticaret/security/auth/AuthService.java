package com.sarikaya.e_ticaret.security.auth;

import com.sarikaya.e_ticaret.entity.Role;
import com.sarikaya.e_ticaret.entity.User;
import com.sarikaya.e_ticaret.repository.UserRepository;
import com.sarikaya.e_ticaret.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
// @RequiredArgsConstructor => final olarak tanımlanmış (değiştirilemez) bağımlılıklar için otomatik constructor oluşturur.
// Bu sayede @Autowired yazmamıza gerek kalmaz, Spring nesneleri otomatik enjekte eder.
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(AuthRequest request){
        // Gelen isteği alıp veritabanına kaydedilecek User nesnesini inşa ediyoruz
        var user = User.builder()
                .username(request.getUsername())
                // Şifreyi veritabanına kaydetmeden önce BCrypt ile güvenli hale getiriyoruz (hashliyoruz)
                .password(passwordEncoder.encode(request.getPassword()))
                // String olan "USER" yerine, artık Role enum'undan gelen sabit Role.USER değerini atıyoruz
                .role(Role.USER)
                // YENİ: Dışarıdan (istekten) isActive değeri gelirse onu kullan, gelmezse varsayılan olarak true (aktif) kabul et
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();

        userRepository.save(user);

        return AuthResponse.builder()
                .status("success")
                // AuthResponse sınıfı "role" bilgisini String olarak beklediği için,
                // user.getRole().name() diyerek Enum değerini (USER/ADMIN) düz metne çeviriyoruz.
                .role(user.getRole().name())
                .message("Kayıt Başarılı")
                .username(user.getUsername())
                .build();
    }

    public Object[] login(AuthRequest request) {
        // 1. ADIM: Kullanıcının girdiği isim ve şifreyi AuthenticationManager'a veriyoruz.
        // Eğer şifre yanlışsa, kullanıcı yoksa VEYA hesabı pasifse (isActive = false) kod burada kesilir ve hata döner.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // 2. ADIM: Doğrulama başarılı olduysa, o kullanıcının tüm bilgilerini veritabanından çekiyoruz.
        var user = userRepository.findByUsername(request.getUsername()).orElseThrow();

        // 3. ADIM: Sisteme başarıyla giriş yapan bu kullanıcıya özel bir JWT (JSON Web Token) bileti kesiyoruz.
        var jwtToken = jwtService.generateToken(user);

        // 4. ADIM: Ön yüze (veya Postman'e) gönderilecek yanıt (JSON) paketini hazırlıyoruz.
        AuthResponse response = AuthResponse.builder()
                .status("success")
                // Enum değerini String olarak yanıt paketine ekliyoruz.
                .role(user.getRole().name())
                // Enum kullandığımız için doğrudan "==" operatörü ile Enum karşılaştırması yapıyoruz.
                .message(user.getRole() == Role.ADMIN ? "admin girişi" : "kullanıcı girişi")
                .username(user.getUsername())
                .build();

        // Hem hazırladığımız JSON yanıtını hem de token'ı Controller'a geri gönderiyoruz.
        return new Object[]{response, jwtToken};
    }
}