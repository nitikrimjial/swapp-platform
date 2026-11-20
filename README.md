# Swapp

**A community platform for exchanging, donating, giving away and requesting everyday items, without money.**

Swapp lets neighbours list things they no longer use, offer their own items in exchange for someone else's, donate or give items away, and post requests for things they need. Every exchange is tracked from offer to hand-over, and only completes when **both** people confirm it.

![Swapp landing page](docs/screenshots/01-landing.png)

## Features

| Area | What works |
| --- | --- |
| **Accounts** | Registration, sign-in/out, BCrypt password hashing, JWT in an HttpOnly `SameSite=Strict` cookie, profile editing, avatar upload, password change (revokes other sessions), "sign out everywhere" |
| **Listings** | Create, edit, delete (soft delete keeps history), up to 6 photos per listing with reorder / cover selection, mark available / unavailable. Four listing types: **Exchange**, **Donation**, **Free giveaway**, **Request** |
| **Discovery** | Full-text search over title and description, filters for category, condition, listing type and location, newest/oldest sort, pagination. Every filter is a database query |
| **Exchange offers** | Offer one of your items for someone else's; accept, decline or withdraw. Business rules enforced transactionally with row locks (see below) |
| **Exchange tracking** | Accepted offers create an exchange record. Both participants must confirm the hand-over; either can cancel, which releases both items |
| **Donations & giveaways** | A separate request workflow: users request the item, the owner chooses who receives it, then marks it handed over |
| **Notifications** | Persistent notifications for offers, acceptances, rejections, cancellations, exchange updates and donation requests, with read/unread state, delivered live over WebSocket |
| **Messaging** | Private one-to-one conversations with item references, unread tracking, read receipts, history paging and realtime delivery |
| **UI** | 19 responsive pages (desktop, tablet, mobile with bottom navigation), light/dark themes, loading skeletons, empty states, toasts, confirmation dialogs, inline validation |

### Exchange rules (enforced on the server)

- Users cannot make offers on their own listings or offer items they don't own.
- Only available items listed for exchange can be offered or requested.
- Duplicate pending offers for the same item pair are rejected, by the service and by a unique index on a generated column.
- Only the owner of the requested item can accept or reject; only the offerer can withdraw.
- Accepting an offer, in **one transaction**: locks both items (`SELECT … FOR UPDATE`, in id order), re-checks availability, reserves both items, creates the exchange, and automatically closes every other pending offer involving either item, notifying the people affected.
- An item can never be in two active exchanges. This is guaranteed by row locking and backed by a unique index.
- Concurrency is covered by tests that fire conflicting accepts (and accept-versus-cancel) at the same instant from separate threads. Exactly one wins.

## Tech stack

- **Backend:** Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA / Hibernate, Bean Validation, Spring WebSocket, Actuator, JJWT
- **Database:** MySQL 8.4 with Flyway migrations (foreign keys, check constraints, unique indexes on generated columns)
- **Frontend:** HTML5, CSS3 and vanilla JavaScript (ES modules), served by Spring Boot. No build step, and a strict Content-Security-Policy with no inline scripts
- **Testing:** JUnit 5, Mockito, MockMvc against a real MySQL database, and Playwright browser journeys
- **Ops:** Dockerfile (multi-stage, non-root), docker-compose, GitHub Actions CI with a MySQL service container

## Screenshots

| | |
| --- | --- |
| ![Browse](docs/screenshots/05-browse.png) | ![Item](docs/screenshots/06-item-detail.png) |
| ![Offer dialog](docs/screenshots/07-offer-dialog.png) | ![Incoming offers](docs/screenshots/13-offers-incoming.png) |
| ![Exchanges](docs/screenshots/15-exchanges.png) | ![Messages](docs/screenshots/17-messages.png) |
| ![Dashboard](docs/screenshots/10-dashboard.png) | ![Mobile](docs/screenshots/22-mobile-item.png) |

More in [`docs/screenshots`](docs/screenshots).

## Getting started

### Prerequisites

- Java 21 (Maven is included via the `mvnw` wrapper)
- MySQL 8.x running locally, **or** Docker

### Option A: Docker Compose (app and database)

```bash
cp .env.example .env          # then edit the secrets in .env
docker compose up --build
# open http://localhost:8080
```

### Option B: run locally with your own MySQL

```bash
# Create a database user (or use root locally)
mysql -u root -p -e "CREATE DATABASE swapp; CREATE USER 'swapp'@'localhost' IDENTIFIED BY 'change-me'; GRANT ALL ON swapp.* TO 'swapp'@'localhost';"

export DB_USERNAME=swapp DB_PASSWORD=change-me
export JWT_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
# open http://localhost:8080
```

Flyway creates the schema and seeds the categories on first start. If `JWT_SECRET` is not set in development, an ephemeral key is generated and a warning is logged. In the `prod` profile the application refuses to start without one.

### Demo data

Fill a running instance with realistic members, listings (with photos), offers, exchanges, donation requests and conversations, all created through the public API:

```bash
cd e2e && npm install && node seed.mjs http://localhost:8080
# demo logins: priya@swapp.demo, arjun@swapp.demo, ... (password: Passw0rd!)
```

### Running the tests

The integration tests use a **real MySQL database** (`swapp_test`, created automatically):

```bash
export TEST_DB_USERNAME=root TEST_DB_PASSWORD=yourpassword   # defaults: root / empty
./mvnw verify
```

67 tests cover unit logic, every controller, authentication and authorisation, item CRUD and filters, image validation, the full offer and exchange journey, the donation workflow, messaging privacy, database constraints and concurrent conflicting operations.

Browser journey (registers two users and walks the whole product through the UI):

```bash
cd e2e && npm install && node journey.mjs http://localhost:8080 ./shots
```

## Environment variables

| Variable | Required in prod | Default (dev) | Description |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes (`prod`) | `default` | `prod` enforces secrets and secure cookies |
| `DB_URL` | yes | `jdbc:mysql://localhost:3306/swapp?...` | JDBC URL of the MySQL database |
| `DB_USERNAME` / `DB_PASSWORD` | yes | `root` / empty | Database credentials |
| `JWT_SECRET` | yes | ephemeral | HMAC signing key, at least 32 bytes |
| `JWT_EXPIRATION` | no | `P7D` | Session lifetime (ISO-8601 duration) |
| `COOKIE_SECURE` | no | `false` (`true` in prod) | Send the auth cookie over HTTPS only |
| `CORS_ALLOWED_ORIGINS` | yes | `http://localhost:8080` | Comma-separated allowed browser origins |
| `STORAGE_TYPE` | no | `database` | `database` stores images in MySQL; `filesystem` stores them on a volume |
| `STORAGE_LOCAL_DIR` | if filesystem | `./data/uploads` | Upload directory for filesystem storage |
| `PORT` | no | `8080` | HTTP port (set automatically by most platforms) |
| `DB_POOL_SIZE` | no | `10` | Hikari connection pool size |

Secrets are only ever read from the environment. Nothing sensitive is committed (see `.gitignore`).

## Documentation

- [Architecture](docs/ARCHITECTURE.md): layers, data model, transactions and locking, security design
- [API reference](docs/API.md): every endpoint with request and response shapes
- [Deployment guide](docs/DEPLOYMENT.md): Railway (recommended), Render with Aiven, AWS EC2 with RDS

## Project structure

```
src/main/java/com/swapp
├── config/        Security, WebSocket, typed configuration properties
├── security/      JWT service, auth filter, cookies, login rate limiter
├── common/        Base entity, error model, pagination, global exception handler
├── user/          Accounts, auth and profile (entity, repository, services, controllers, DTOs)
├── category/      Category reference data
├── item/          Listings, images, search specifications, item locking
├── offer/         Exchange offers
├── exchange/      Exchange tracking and two-sided completion
├── donation/      Donation and giveaway requests
├── notification/  Persistent notifications
├── messaging/     Conversations and messages
├── realtime/      WebSocket hub and authenticated handshake
├── storage/       Image validation and storage (database or filesystem)
└── stats/         Public landing-page stats
src/main/resources
├── db/migration/  Flyway SQL migrations
└── static/        HTML pages, css/app.css, js/ (api, ui, app and one module per page)
```

## License

[MIT](LICENSE)
