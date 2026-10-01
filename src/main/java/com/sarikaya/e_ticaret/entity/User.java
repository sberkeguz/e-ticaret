package com.sarikaya.e_ticaret.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;


// Entity=bu sınıf veritabanı
@Entity
//table adı
@Table(name="users")
@Data
@Builder
//parametresiz constructor
@NoArgsConstructor
//Tüm değişkenleri içeren constructor oluşturur
@AllArgsConstructor
public class User implements UserDetails {
    //Primary Key
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //sütun özellikleri
    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
public boolean isAccountNonExpired(){return true;}
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
public boolean isCredentialsNonExpired(){return true;}
    @Override
public boolean isEnabled() {return true;}
}