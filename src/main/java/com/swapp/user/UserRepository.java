package com.swapp.user;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByDeletedAtIsNull();

    Page<User> findBySuspendedAtIsNotNull(Pageable pageable);

    long countBySuspendedAtIsNotNull();
}
