# Swapp: project context for Claude Code

Swapp is a community **barter, donation and sharing** web app: users list items, offer their own items in exchange, donate or give things away, post requests, chat, and track exchanges to completion. There is **no money** anywhere in the product, so trades are called "exchanges", never "purchases". It is the owner's portfolio project, aimed at recruiters, and should stay production-quality.

- Repo (public): https://github.com/nitikrimjial/swapp-platform. The older private repo `nitikrimjial/swapp` is an abandoned early skeleton; leave it untouched.
- Detailed docs: `README.md`, `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/DEPLOYMENT.md`.

## Stack

- Java 21, Spring Boot 3.5.6 (Web, Security, Data JPA, Validation, WebSocket, Actuator), JJWT 0.12, Lombok, Maven wrapper (`mvnw`).
- **PostgreSQL 17** with Flyway (`src/main/resources/db/migration`: V1 schema, V2 categories, V3 coordinates and trigram indexes). The project switched from MySQL on 2026-11-21 at the owner's request; there is no MySQL code left.
- Frontend: static HTML, CSS and vanilla ES modules in `src/main/resources/static`, served by Spring Boot from the same origin. No build step and no framework.
- Tests: JUnit 5, Mockito, and MockMvc against a **real PostgreSQL** database (`swapp_test`), plus Playwright browser scripts in `e2e/`.

## Layout

```
src/main/java/com/swapp/
  config/        SecurityConfig (CSP, route rules, CORS), WebSocketConfig, AppProperties (app.* config)
  security/      JwtService, JwtAuthenticationFilter, TokenAuthenticator, AuthCookies, LoginRateLimiter
  common/        BaseEntity, ApiException, ApiError, GlobalExceptionHandler, PageResponse, GeoPoint
  user/ category/ item/ offer/ exchange/ donation/ notification/ messaging/ realtime/ storage/ stats/
src/main/resources/static/
  *.html (18 pages, plus error/404.html), css/app.css (design tokens, light and dark)
  js/api.js (fetch wrapper), js/ui.js (html`` escaping template, components, toasts, dialogs),
  js/app.js (header, auth guard, badges, WebSocket), js/location.js (GPS, Nominatim, picker),
  js/images.js (client-side resize), js/pages/<page>.js
e2e/journey.mjs  22-step UI journey (two users, simulated GPS)    e2e/seed.mjs  demo data via the API
```

## Rules to keep (do not regress)

- **Locking order for every state change:** lock items first through `ItemLocker` (`SELECT … FOR UPDATE ORDER BY id`), then the offer, exchange or donation-request row, then re-check state. Read foreign keys with projection `findRefs` queries before locking, so no stale entities are in the persistence context. This is what prevents conflicting exchanges; `ConcurrencyIntegrationTest` proves it.
- The database backs the business rules with partial unique indexes (one pending offer per item pair, one active exchange per item, one active donation request per user and item) and CHECK constraints. In a CHECK, remember that `NULL` passes, so spell out `IS NOT NULL` where it matters.
- Controllers stay thin, services hold the rules, and APIs return DTO records only, never entities. Errors are raised as `ApiException` and come out as a uniform `ApiError` JSON body.
- Resources a user may not see return **404**, not 403, so ids can't be probed. Ownership and participant checks live in the services.
- Notifications are saved in the business transaction and pushed over WebSocket **after commit** (`RealtimeEvent`, then `RealtimeHub` via `@TransactionalEventListener(AFTER_COMMIT)`).
- Auth uses a JWT in an HttpOnly `SameSite=Strict` cookie (`swapp_token`), or a Bearer header for API clients. CSRF tokens are off on purpose. `users.token_version` revokes tokens on password change and "sign out everywhere".
- **Frontend security:** CSP is `script-src 'self'`, so never add inline scripts or `on*=` handlers. Render dynamic data only through the `html` tagged template in `ui.js`, which escapes it. `?next=` redirects go through `safeNext`. `/api/auth/session` returns `{user:null}` for visitors, which avoids 401 console noise.
- **Location:** coordinates are optional, rounded to 3 decimals (about 110 m), and returned only to the owner; others see `distanceKm`. Radius search applies a bounding box first (index `idx_items_geo`), then the exact great-circle distance in SQL (`ItemSpecifications.distanceKm`). Nominatim (OpenStreetMap) is called from the browser only on explicit user action, with attribution, as its usage policy requires; CSP `connect-src` allows it.
- Images are validated by magic bytes server-side (≤ 5 MB, ≤ 6 per listing) and resized client-side to at most 1600 px JPEG before upload. Storage defaults to `database` (`stored_files.data BYTEA`). The upgrade path is S3 or R2 behind the existing `StorageService` interface.
- Items are soft-deleted (`status = REMOVED`) so exchange history keeps its references.

## Commands

```bash
./mvnw -B verify                         # all 78 tests (needs PostgreSQL with a swapp_test database)
./mvnw spring-boot:run                   # app on :8080 (needs a swapp database); JWT_SECRET optional in dev
cd e2e && npm install
node seed.mjs http://localhost:8080      # demo members/listings/offers (password Passw0rd!, e.g. priya@swapp.demo)
node journey.mjs http://localhost:8080 ./shots   # UI journey; BROWSER_CHANNEL=msedge|chrome
```

Test DB defaults: `jdbc:postgresql://localhost:5432/swapp_test`, user `postgres`, empty password (override with `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`). CI (`.github/workflows/ci.yml`) runs `verify` against a postgres:17 service container, then builds the Docker image.

## Status (as of 2026-11-21)

Done and verified: all features in the README, PostgreSQL migration, near-me radius search with nearest-first sorting, extra filters (multiple conditions, posted within, photos only, hide mine), client-side image resizing, demo seed script, CI green, 78 tests passing, and the browser journey passing with zero console errors.

**Not done yet: production deployment.** The plan the owner chose is **Render (free web service) + Neon (free PostgreSQL with no expiry)**. `render.yaml` is ready: Render generates `JWT_SECRET`, and the owner must supply `DB_URL=jdbc:postgresql://<neon-host>/neondb?sslmode=require`, `DB_USERNAME` and `DB_PASSWORD`. Steps are in `docs/DEPLOYMENT.md`. The owner has to create both accounts, either deploying through the Blueprint themselves or sharing a Render API key and the Neon details. Never claim it is live without checking `/actuator/health` on the real URL. After deploying: run `seed.mjs` and `journey.mjs` against the live URL.

## Ideas discussed for later (not started)

- A map view (Leaflet with OSM tiles, needing CSP changes) on the item and browse pages.
- PostGIS (`geography` with GiST and `ST_DWithin`) if listings reach millions.
- Moving images to Cloudflare R2 or S3 plus a CDN; Redis for caching, sessions and cross-instance WebSocket fan-out; read replicas.
- Ratings and reviews after completed exchanges; reporting and moderation (an `ADMIN` role already exists).
