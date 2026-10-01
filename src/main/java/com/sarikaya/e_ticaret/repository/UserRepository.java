package com.sarikaya.e_ticaret.repository;

import com.sarikaya.e_ticaret.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

//JpaRepository => save() findAll() findById() gibigibi temels ql sorguları

public interface UserRepository extends JpaRepository<User, Long> {
Optional<User> findByUsername(String username);

}