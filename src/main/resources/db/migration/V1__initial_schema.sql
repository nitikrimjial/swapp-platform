-- Swapp initial schema. All timestamps are stored in UTC.

CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(80)  NOT NULL,
    bio             VARCHAR(500) NULL,
    location        VARCHAR(120) NULL,
    avatar_key      VARCHAR(100) NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    token_version   INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE categories (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    name        VARCHAR(60) NOT NULL,
    slug        VARCHAR(60) NOT NULL,
    icon        VARCHAR(40) NOT NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT uk_categories_slug UNIQUE (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE items (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id        BIGINT        NOT NULL,
    category_id     BIGINT        NOT NULL,
    title           VARCHAR(120)  NOT NULL,
    description     VARCHAR(4000) NOT NULL,
    item_condition  VARCHAR(20)   NOT NULL,
    listing_type    VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    location        VARCHAR(120)  NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_items_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_items_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_items_condition CHECK (item_condition IN ('NEW', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS')),
    CONSTRAINT ck_items_listing_type CHECK (listing_type IN ('EXCHANGE', 'DONATION', 'GIVEAWAY', 'REQUEST')),
    CONSTRAINT ck_items_status CHECK (status IN ('AVAILABLE', 'RESERVED', 'UNAVAILABLE', 'COMPLETED', 'REMOVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_items_browse ON items (status, listing_type, created_at);
CREATE INDEX idx_items_status_created ON items (status, created_at);
CREATE INDEX idx_items_owner_status ON items (owner_id, status);
CREATE INDEX idx_items_category_status ON items (category_id, status);
CREATE INDEX idx_items_location ON items (location);

CREATE TABLE item_images (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    item_id       BIGINT       NOT NULL,
    storage_key   VARCHAR(100) NOT NULL,
    content_type  VARCHAR(50)  NOT NULL,
    position      INT          NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_images_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT uk_item_images_key UNIQUE (storage_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_item_images_item_position ON item_images (item_id, position);

CREATE TABLE stored_files (
    storage_key   VARCHAR(100) NOT NULL,
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    data          MEDIUMBLOB   NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (storage_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE offers (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    requested_item_id  BIGINT        NOT NULL,
    offered_item_id    BIGINT        NOT NULL,
    offerer_id         BIGINT        NOT NULL,
    recipient_id       BIGINT        NOT NULL,
    message            VARCHAR(1000) NULL,
    status             VARCHAR(20)   NOT NULL,
    resolution_note    VARCHAR(255)  NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    -- Enforces "no duplicate active offers for the same item combination" at the database level.
    pending_key        VARCHAR(50) GENERATED ALWAYS AS (
                           CASE WHEN status = 'PENDING' THEN CONCAT(requested_item_id, ':', offered_item_id) END
                       ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_offers_requested_item FOREIGN KEY (requested_item_id) REFERENCES items (id),
    CONSTRAINT fk_offers_offered_item FOREIGN KEY (offered_item_id) REFERENCES items (id),
    CONSTRAINT fk_offers_offerer FOREIGN KEY (offerer_id) REFERENCES users (id),
    CONSTRAINT fk_offers_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT uk_offers_pending UNIQUE (pending_key),
    CONSTRAINT ck_offers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_offers_distinct_items CHECK (requested_item_id <> offered_item_id),
    CONSTRAINT ck_offers_distinct_users CHECK (offerer_id <> recipient_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_offers_recipient ON offers (recipient_id, status, created_at);
CREATE INDEX idx_offers_offerer ON offers (offerer_id, status, created_at);
CREATE INDEX idx_offers_requested_item ON offers (requested_item_id, status);
CREATE INDEX idx_offers_offered_item ON offers (offered_item_id, status);

CREATE TABLE exchanges (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    offer_id                BIGINT      NOT NULL,
    owner_id                BIGINT      NOT NULL,
    requester_id            BIGINT      NOT NULL,
    owner_item_id           BIGINT      NOT NULL,
    requester_item_id       BIGINT      NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    owner_confirmed_at      DATETIME(6) NULL,
    requester_confirmed_at  DATETIME(6) NULL,
    completed_at            DATETIME(6) NULL,
    cancelled_at            DATETIME(6) NULL,
    cancelled_by_id         BIGINT      NULL,
    version                 BIGINT      NOT NULL DEFAULT 0,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    -- An item may take part in at most one active exchange (defence in depth on top of row locking).
    active_owner_item       BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN owner_item_id END) STORED,
    active_requester_item   BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN requester_item_id END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_exchanges_offer FOREIGN KEY (offer_id) REFERENCES offers (id),
    CONSTRAINT fk_exchanges_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_exchanges_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_exchanges_owner_item FOREIGN KEY (owner_item_id) REFERENCES items (id),
    CONSTRAINT fk_exchanges_requester_item FOREIGN KEY (requester_item_id) REFERENCES items (id),
    CONSTRAINT fk_exchanges_cancelled_by FOREIGN KEY (cancelled_by_id) REFERENCES users (id),
    CONSTRAINT uk_exchanges_offer UNIQUE (offer_id),
    CONSTRAINT uk_exchanges_active_owner_item UNIQUE (active_owner_item),
    CONSTRAINT uk_exchanges_active_requester_item UNIQUE (active_requester_item),
    CONSTRAINT ck_exchanges_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_exchanges_owner ON exchanges (owner_id, status, updated_at);
CREATE INDEX idx_exchanges_requester ON exchanges (requester_id, status, updated_at);

CREATE TABLE donation_requests (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    item_id          BIGINT        NOT NULL,
    requester_id     BIGINT        NOT NULL,
    owner_id         BIGINT        NOT NULL,
    message          VARCHAR(1000) NULL,
    status           VARCHAR(20)   NOT NULL,
    resolution_note  VARCHAR(255)  NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    active_key       VARCHAR(50) GENERATED ALWAYS AS (
                         CASE WHEN status IN ('PENDING', 'ACCEPTED') THEN CONCAT(item_id, ':', requester_id) END
                     ) STORED,
    accepted_item    BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACCEPTED' THEN item_id END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_donation_requests_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_donation_requests_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_donation_requests_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT uk_donation_requests_active UNIQUE (active_key),
    CONSTRAINT uk_donation_requests_accepted_item UNIQUE (accepted_item),
    CONSTRAINT ck_donation_requests_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT ck_donation_requests_users CHECK (requester_id <> owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_donation_requests_owner ON donation_requests (owner_id, status, created_at);
CREATE INDEX idx_donation_requests_requester ON donation_requests (requester_id, status, created_at);
CREATE INDEX idx_donation_requests_item ON donation_requests (item_id, status);

CREATE TABLE conversations (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    user_one_id      BIGINT      NOT NULL,
    user_two_id      BIGINT      NOT NULL,
    last_message_at  DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_conversations_user_one FOREIGN KEY (user_one_id) REFERENCES users (id),
    CONSTRAINT fk_conversations_user_two FOREIGN KEY (user_two_id) REFERENCES users (id),
    CONSTRAINT uk_conversations_pair UNIQUE (user_one_id, user_two_id),
    CONSTRAINT ck_conversations_order CHECK (user_one_id < user_two_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_conversations_user_one ON conversations (user_one_id, last_message_at);
CREATE INDEX idx_conversations_user_two ON conversations (user_two_id, last_message_at);

CREATE TABLE messages (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id  BIGINT        NOT NULL,
    sender_id        BIGINT        NOT NULL,
    item_id          BIGINT        NULL,
    body             VARCHAR(2000) NOT NULL,
    read_at          DATETIME(6)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id),
    CONSTRAINT fk_messages_item FOREIGN KEY (item_id) REFERENCES items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_messages_conversation ON messages (conversation_id, id);
CREATE INDEX idx_messages_unread ON messages (conversation_id, sender_id, read_at);

CREATE TABLE notifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_id  BIGINT       NOT NULL,
    type          VARCHAR(40)  NOT NULL,
    title         VARCHAR(160) NOT NULL,
    body          VARCHAR(500) NOT NULL,
    link          VARCHAR(255) NULL,
    read_at       DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, id);
CREATE INDEX idx_notifications_unread ON notifications (recipient_id, read_at);
