package com.sarikaya.e_ticaret.repository;

import com.sarikaya.e_ticaret.security.entity.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    //son50 giriş
    List<LoginHistory> findTop50ByUserIdOrderByLoggedInAtDesc(Long userId);

    //kullanıcı silerken geçmişi de silmek
    void deleteByUserId(Long userId);
}
