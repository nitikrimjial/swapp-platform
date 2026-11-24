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
PostgreSQL 17 (Flyway-managed schema)
```

The code is organised **by feature** (`item`, `offer`, `exchange`, …). Each feature owns its entity, repository, service, controller and DTOs. Controllers contain no business logic. Entities are never serialised directly: every response is a DTO record.

## Data model

| Table | Purpose | Key constraints |
| --- | --- | --- |
| `users` | Accounts | unique `lower(email)`; `token_version` for revocation; optional home `latitude`/`longitude` |
| `categories` | Seeded reference data | unique `name`, `slug` |
| `items` | Listings | FK owner and category; CHECK on condition, type, status and coordinates; `version` for optimistic locking; browse indexes; partial geo index; `pg_trgm` GIN indexes on title, description and location |
| `item_images` | Ordered listing photos | FK item `ON DELETE CASCADE`; unique storage key |
| `stored_files` | Image bytes (database storage mode) | PK storage key |
| `offers` | Exchange offers | FKs to both items and both users; CHECK requested ≠ offered and offerer ≠ recipient; **partial unique index** on (requested, offered) `WHERE status = 'PENDING'`, so only one pending offer per item pair |
| `exchanges` | Accepted offers | unique `offer_id`; **partial unique indexes** on each item `WHERE status = 'ACTIVE'`, so an item is in at most one active exchange |
| `donation_requests` | Requests for donations and giveaways | partial unique indexes: one active request per user per item, and one accepted request per item |
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

The consistent lock order serialises conflicting operations (two accepts for the same item, accept against cancel, accept against hiding the listing) and avoids deadlocks. In the rare case the database reports a lock conflict anyway, it is translated into HTTP 409 with a "please retry" message. The partial unique indexes are a second line of defence that the database itself enforces.

`ConcurrencyIntegrationTest` starts competing operations on separate threads behind a shared latch and asserts that exactly one succeeds and the database ends in a consistent state.

## Notifications and realtime

`NotificationService.notify` saves a notification as part of the caller's transaction and publishes a `RealtimeEvent`. `RealtimeHub` handles that event with `@TransactionalEventListener(AFTER_COMMIT)`, so nothing is pushed if the transaction rolls back. The hub keeps thread-safe WebSocket sessions per user. The browser reconnects with exponential backoff and falls back to polling while disconnected. Messages use the same mechanism (`message` and `messages-read` events).

## Security design

- **Passwords:** BCrypt (strength 12 in production). Login takes the same time whether or not the email exists. Failed logins are limited per client address + email, and per email alone with a higher ceiling, so guesses spread over many addresses are stopped too. Passwords are limited to 72 UTF-8 *bytes* (bcrypt's input limit), not just 72 characters.
- **Account recovery and verification (OWASP Forgot Password Cheat Sheet):** reset and verification links carry 256 random bits in the URL fragment (never sent to servers or in `Referer`), and only their SHA-256 hash is stored (`user_tokens`). They are single-use, short-lived (1 hour for resets, 2 days for verification), and replaced when a new one is issued. Redeeming one locks its row, so two simultaneous uses cannot both succeed. "Forgot password" answers 202 for any email and does its work in the background, so neither the response nor its timing reveals whether an account exists. Requests are capped per client address and per target email, so nobody can flood an inbox. A reset revokes every session and emails the member, as does a password change in Settings. When `REQUIRE_EMAIL_VERIFICATION=true`, a Spring Security rule withholds the `EMAIL_VERIFIED` authority from unconfirmed members on endpoints that reach other people. The flag is re-read from the database on every request, so confirming takes effect immediately.
- **Sign in with Google (OpenID Connect):** the authorization code flow runs on the server (Spring Security OAuth2 Client), with PKCE, `state` and `nonce`, so no Google script runs on our pages and the CSP is unchanged. Spring validates the ID token's signature, issuer, audience, expiry and nonce. The handshake state lives in a short-lived server session (`swapp_oauth`, `SameSite=Lax` so it survives Google's redirect back) that is invalidated on return; from then on the member has the normal JWT cookie. Members are matched on Google's `sub` (`user_identities`, unique per provider and subject), never on email, and only Google-verified emails are accepted. Matching an existing account by email connects Google and emails a security alert; if that account's email was never verified, its password is removed and its sessions revoked, which stops an attacker who pre-registered someone else's address (pre-account-takeover). A new person gets no account until they accept the Terms on `/complete-signup.html`; meanwhile a 15-minute signed token in an HttpOnly cookie, with its own JWT audience so it can never act as a session, proves which Google account they used. Starting the flow is rate-limited per address, because each start creates a server session. Google-only members have no password (`password_hash` is nullable); they set one through the emailed reset link, can't disconnect Google until they have one, and delete their account by signing in within the last 10 minutes instead of re-entering a password.
- **Data rights (DPDP Act 2023, GDPR):** members can download all their data as JSON and delete their account themselves. Deletion re-checks the password (or a recent sign-in for Google-only members), is refused while an exchange or hand-over is in progress (someone may be on their way to meet them), and follows the usual locking order: the member's items and the items they requested first, then offers and requests. The `users` row is kept but anonymised (`deleted-<id>@deleted.invalid`, "Deleted member"), so completed exchanges and the other person's chat history stay intact. A CHECK constraint guarantees a deleted row holds no personal data. Listings are soft-deleted, and photos, avatar, notifications and tokens are erased. The email address becomes free to register again.
- **Trust and safety:** members can report a listing, a member or a message they received. Reports store a snapshot of the content as evidence, so editing afterwards can't hide it. A partial unique index allows one open report per reporter and target, and CHECK constraints make each target type carry exactly the reference it needs. Blocks (`user_blocks`) work both ways and are enforced in the message, offer and donation-request services. Moderators (`ADMIN`) work through a queue at `/admin.html`. Taking a listing down, or suspending a member, resolves every related open report and notifies the reporters in general terms. Suspension follows the usual locking order (items first) and cancels the member's exchanges and hand-overs in progress, because the other person may be about to meet them. It also hides their listings and revokes their sessions. Suspended and deleted accounts cannot authenticate, be contacted or be viewed.
- **Email delivery:** `EmailMessage` events are published inside the business transaction and sent after commit on a virtual thread by `EmailDispatcher`, with retries. A slow provider never delays a request, and nobody is emailed about a rolled-back change. Delivery goes through the `EmailSender` interface: `LoggingEmailSender` in development, `SmtpEmailSender` (any SMTP relay, such as Brevo or Amazon SES) in production. The app refuses to start if verification is required but email cannot actually be delivered. Delivery is best-effort (no outbox table); every flow that depends on an email lets the user request another.
- **Abuse limits:** a `RateLimitFilter` throttles sign-up (per address per hour), sign-in and password change (per address per minute; each costs a bcrypt hash, which is expensive on a small instance) and all writes (per user per minute), answering 429 with `Retry-After`. The client address comes from Tomcat's `RemoteIpValve` (`forward-headers-strategy: native`), which trusts `X-Forwarded-For` only from internal proxy addresses, so a client can't spoof it. Each user may have 100 active listings and 30 MB of photos, and uploads pause site-wide at 400 MB, so no account can fill the free database. Each user may hold 8 WebSockets, and revoking tokens closes them. All counters are in memory, which suits a single instance; several instances would move them to Redis.
- **Sessions:** a stateless HS256 JWT containing the user id and `token_version`. It is delivered as an `HttpOnly; SameSite=Strict; Secure` cookie, so scripts can't read it and browsers never send it cross-site. That is why Spring's CSRF tokens are disabled. API clients may send `Authorization: Bearer` instead. Incrementing `token_version` (password change, "sign out everywhere") revokes every existing token.
- **Authorisation:** Spring Security route rules (public read endpoints, authenticated everything else, `/api/admin/**` limited to `ADMIN`) plus ownership and participant checks in every service method. Resources a user may not see return 404, so ids can't be probed.
- **Input:** Bean Validation on every request DTO; JPA Criteria and parameterised queries only (LIKE wildcards in search input are escaped).
- **Uploads:** size limits (5 MB per image, 6 per listing). The type is detected from file signatures, never from the client's declared type. Files are stored under random server-generated keys and served with `nosniff`. Location metadata is removed twice: the browser always re-encodes photos (never falling back to the original file), and the server strips EXIF/XMP (APP1) and IPTC (APP13) segments from JPEGs without re-encoding (`JpegMetadataStripper`), so a phone photo can't reveal where the member lives.
- **Headers:** strict Content-Security-Policy (`script-src 'self'`, `connect-src` limited to the app itself and Nominatim), `X-Frame-Options: DENY`, Referrer-Policy, `Permissions-Policy` (geolocation for our own pages only), `Cross-Origin-Opener-Policy: same-origin`, and a one-year HSTS when served over HTTPS.
- **Supply chain:** Dependabot opens weekly update PRs (Maven, Docker, GitHub Actions, npm) and CodeQL scans the Java and JavaScript on every push.
- **Frontend:** every dynamic value is HTML-escaped by the `html` tagged template in `ui.js`; redirects via `?next=` accept only same-origin paths.
- **Secrets:** environment variables only. The `prod` profile has no defaults for the database or JWT secret.

## Location search ("near me")

Listings and profiles can carry optional coordinates, set from the browser's geolocation or from an OpenStreetMap (Nominatim) place search. The server rounds them to three decimals (about 110 m), so exact addresses are never stored. Coordinates are only returned to a listing's owner; everyone else sees a distance.

A radius query (`lat`, `lng`, `radiusKm`) runs in two stages inside one SQL statement:

1. **Bounding box:** `latitude`/`longitude BETWEEN` the square around the circle. This uses the partial index `idx_items_geo` and discards almost every row cheaply.
2. **Exact distance:** the spherical law of cosines, computed in SQL (`6371 * acos(…)`), filters the remaining rows to the true circle and orders them for `sort=distance`.

This needs no extensions and is accurate to well under 1% at city scale. If the dataset grows to millions of rows, the natural upgrade is PostGIS (`geography` column with a GiST index and `ST_DWithin`).

## Image storage

`StorageService` has two implementations, selected with `STORAGE_TYPE`:

- `database` (default): bytes live in `stored_files` (`bytea`). Images therefore persist across redeploys on any platform and are covered by database backups. The browser resizes photos to at most 1600 px (JPEG) before upload, so a typical photo takes 200-400 KB.
- `filesystem`: files go on a mounted persistent volume (for example EBS on EC2).

## Error handling

`GlobalExceptionHandler` maps domain exceptions (`ApiException`), validation errors (with per-field messages), integrity and locking conflicts, upload limits and unexpected errors to one JSON shape:

```json
{ "timestamp": "...", "status": 409, "error": "Conflict", "message": "This item is no longer available", "path": "/api/offers", "fieldErrors": { } }
```

Unexpected errors are logged with stack traces on the server. Clients only receive a generic message.
