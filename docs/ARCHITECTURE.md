# Architecture

## Overview

Swapp is a single Spring Boot application that serves both the REST API (`/api/**`), the WebSocket endpoint (`/ws`) and the static frontend. Serving everything from one origin keeps deployment simple (one container, one URL) and lets the session cookie be `SameSite=Strict`.

```
Browser (HTML + ES modules)
   │  fetch /api/**  (HttpOnly JWT cookie)        WebSocket /ws (same cookie)
   ▼                                               ▼
Spring Security filter chain ──► JwtAuthenticationFilter ──► TokenAuthenticator (signature, expiry, token version)
   │
Controllers (HTTP mapping, validation only)
   │
Services (business rules, transactions, row locks, notifications)
   │                         │
Repositories (Spring Data JPA)   ApplicationEvent ──► RealtimeHub (after commit) ──► WebSocket sessions
   │
MySQL 8 (Flyway-managed schema)
```

The code is organised **by feature** (`item`, `offer`, `exchange`, …). Each feature owns its entity, repository, service, controller and DTOs. Controllers contain no business logic. Entities are never serialised directly: every response is a DTO record.

## Data model

| Table | Purpose | Key constraints |
| --- | --- | --- |
| `users` | Accounts | unique `email`; `token_version` for revocation |
| `categories` | Seeded reference data | unique `name`, `slug` |
| `items` | Listings | FK owner and category; CHECK on condition, type and status; `version` for optimistic locking; browse indexes |
| `item_images` | Ordered listing photos | FK item `ON DELETE CASCADE`; unique storage key |
| `stored_files` | Image bytes (database storage mode) | PK storage key |
| `offers` | Exchange offers | FKs to both items and both users; CHECK requested ≠ offered and offerer ≠ recipient; **unique `pending_key`** generated column, so only one pending offer per item pair |
| `exchanges` | Accepted offers | unique `offer_id`; **unique `active_owner_item` / `active_requester_item`** generated columns, so an item is in at most one active exchange |
| `donation_requests` | Requests for donations and giveaways | **unique `active_key`** (one active request per user per item) and **unique `accepted_item`** (one accepted request per item) |
| `conversations` | One thread per user pair | unique (`user_one_id`, `user_two_id`), CHECK `user_one_id < user_two_id` |
| `messages` | Chat messages | FK conversation `ON DELETE CASCADE`; optional item reference; `read_at` |
| `notifications` | Persistent notifications | FK recipient; `read_at` |

Items are **soft-deleted** (`status = REMOVED`) so that completed exchanges, offers and messages keep their references.

### Item lifecycle

```
AVAILABLE ⇄ UNAVAILABLE            (owner toggles; hiding closes pending offers and requests)
AVAILABLE → RESERVED               (offer accepted / donation request accepted)
RESERVED  → AVAILABLE              (exchange cancelled / hand-over called off)
RESERVED  → COMPLETED              (both parties confirm / owner marks handed over)
AVAILABLE | UNAVAILABLE → REMOVED  (owner deletes)
```

## Transactions and concurrency

Every state-changing workflow runs in one transaction and takes **pessimistic row locks in a fixed order**:

1. The relevant **items**, locked via `ItemLocker` (`SELECT … FOR UPDATE … ORDER BY id`).
2. Then the **offer, exchange or donation request** row.

Before locking, services read only foreign keys through projection queries (`findRefs`), so no stale entity state is in the persistence context when the locked rows are loaded. After locking, all preconditions are re-checked.

The consistent lock order serialises conflicting operations (two accepts for the same item, accept against cancel, accept against hiding the listing) and avoids deadlocks. In the rare case the database reports a lock conflict anyway, it is translated into HTTP 409 with a "please retry" message. The unique indexes on generated columns are a second line of defence that the database itself enforces.

`ConcurrencyIntegrationTest` starts competing operations on separate threads behind a shared latch and asserts that exactly one succeeds and the database ends in a consistent state.

## Notifications and realtime

`NotificationService.notify` saves a notification as part of the caller's transaction and publishes a `RealtimeEvent`. `RealtimeHub` handles that event with `@TransactionalEventListener(AFTER_COMMIT)`, so nothing is pushed if the transaction rolls back. The hub keeps thread-safe WebSocket sessions per user. The browser reconnects with exponential backoff and falls back to polling while disconnected. Messages use the same mechanism (`message` and `messages-read` events).

## Security design

- **Passwords:** BCrypt (strength 12 in production). Login takes the same time whether or not the email exists, and failed logins are rate-limited per client address and email.
- **Sessions:** a stateless HS256 JWT containing the user id and `token_version`. It is delivered as an `HttpOnly; SameSite=Strict; Secure` cookie, so scripts can't read it and browsers never send it cross-site. That is why Spring's CSRF tokens are disabled. API clients may send `Authorization: Bearer` instead. Incrementing `token_version` (password change, "sign out everywhere") revokes every existing token.
- **Authorisation:** Spring Security route rules (public read endpoints, authenticated everything else, `/api/admin/**` limited to `ADMIN`) plus ownership and participant checks in every service method. Resources a user may not see return 404, so ids can't be probed.
- **Input:** Bean Validation on every request DTO; JPA Criteria and parameterised queries only (LIKE wildcards in search input are escaped).
- **Uploads:** size limits (5 MB per image, 6 per listing). The type is detected from file signatures, never from the client's declared type. Files are stored under random server-generated keys and served with `nosniff`.
- **Headers:** strict Content-Security-Policy (`script-src 'self'`, no inline scripts), `X-Frame-Options: DENY`, Referrer-Policy, and HSTS when served over HTTPS.
- **Frontend:** every dynamic value is HTML-escaped by the `html` tagged template in `ui.js`; redirects via `?next=` accept only same-origin paths.
- **Secrets:** environment variables only. The `prod` profile has no defaults for the database or JWT secret.

## Image storage

`StorageService` has two implementations, selected with `STORAGE_TYPE`:

- `database` (default): bytes live in `stored_files`. Images therefore persist across redeploys on any platform and are covered by database backups.
- `filesystem`: files go on a mounted persistent volume (for example EBS on EC2).

## Error handling

`GlobalExceptionHandler` maps domain exceptions (`ApiException`), validation errors (with per-field messages), integrity and locking conflicts, upload limits and unexpected errors to one JSON shape:

```json
{ "timestamp": "...", "status": 409, "error": "Conflict", "message": "This item is no longer available", "path": "/api/offers", "fieldErrors": { } }
```

Unexpected errors are logged with stack traces on the server. Clients only receive a generic message.
