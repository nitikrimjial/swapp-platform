-- Record when each member accepted the Terms and Privacy Policy (and confirmed they are 18 or older), and which
-- version, so consent can be shown later. Accounts created before this existed have no record.

ALTER TABLE users
    ADD COLUMN terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN terms_version     VARCHAR(20);
ALTER TABLE users ADD CONSTRAINT ck_users_terms CHECK ((terms_accepted_at IS NULL) = (terms_version IS NULL));
