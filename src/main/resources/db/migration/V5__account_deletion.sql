-- Self-service account deletion. The row stays (exchanges, offers and messages reference it, and the other party
-- keeps their history), but every personal field is erased and the account can never sign in again.

ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

-- Defence in depth: a deleted account must not keep personal data. NULL passes a CHECK, so each column is tested.
ALTER TABLE users ADD CONSTRAINT ck_users_deleted_erased CHECK (
    deleted_at IS NULL OR (
        bio IS NULL AND location IS NULL AND avatar_key IS NULL AND latitude IS NULL AND longitude IS NULL
        AND email_verified_at IS NULL AND email LIKE 'deleted-%@deleted.invalid'
    )
);
