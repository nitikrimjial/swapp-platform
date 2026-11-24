package com.swapp.user.identity;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    Optional<UserIdentity> findByUserIdAndProvider(Long userId, IdentityProvider provider);

    List<UserIdentity> findByUserIdOrderByCreatedAtAsc(Long userId);

    @Modifying
    @Query("delete from UserIdentity i where i.user.id = :userId")
    int deleteByUserId(Long userId);
}
