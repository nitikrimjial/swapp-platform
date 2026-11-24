package com.swapp.user.identity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.swapp.common.BaseEntity;
import com.swapp.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A provider account (for example a Google account) that can sign in to a Swapp account. */
@Entity
@Table(name = "user_identities")
@Getter
@Setter
@NoArgsConstructor
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private IdentityProvider provider;

    /** The provider's permanent account id (the OpenID Connect "sub" claim). */
    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;
}
