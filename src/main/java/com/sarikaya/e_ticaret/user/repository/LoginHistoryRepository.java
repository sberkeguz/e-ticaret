package com.sarikaya.e_ticaret.user.repository;

import com.sarikaya.e_ticaret.user.entity.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    List<LoginHistory> findTop50ByUserIdOrderByLoggedInAtDesc(Long userId);

    void deleteByUserId(Long userId);
}
