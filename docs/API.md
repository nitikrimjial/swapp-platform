# API reference

Base path: `/api`. All requests and responses are JSON unless noted. Authenticated endpoints accept either the `swapp_token` HttpOnly cookie (set by register/login) or an `Authorization: Bearer <token>` header.

Paginated responses share this envelope:

```json
{ "content": [ ... ], "page": 0, "size": 12, "totalElements": 42, "totalPages": 4, "last": false }
```

`page` is zero-based; `size` is capped at 50. Errors always use the [error shape](ARCHITECTURE.md#error-handling).

## Auth

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| POST | `/auth/register` | public | `{email, password, displayName, location?, acceptTerms}` → 201 `{user, accessToken, expiresAt}` + cookie. `acceptTerms` must be `true` (the member confirms they are 18 or older and accepts the Terms and Privacy Policy); the time and terms version are recorded |
| POST | `/auth/login` | public | `{email, password}` → `{user, accessToken, expiresAt}` + cookie. 401 on bad credentials, 429 when rate-limited |
| POST | `/auth/logout` | public | Clears the cookie → 204 |
| GET | `/auth/session` | public | `{user, emailVerificationRequired}`; `user` is `null` for anonymous visitors |
| GET | `/auth/me` | user | Current user |
| POST | `/auth/forgot-password` | public | `{email}` → 202 always (the same answer whether or not the email is registered). Emails a single-use reset link valid for 1 hour |
| POST | `/auth/reset-password` | public | `{token, newPassword}` → 204. Revokes every session, clears the cookie and emails a security alert. 400 if the link is invalid, used or expired |
| POST | `/auth/verify-email` | public | `{token}` → 204. Works without a session, so the link opens on any device. 400 if invalid, used or expired |

Password rules: 8-72 characters, at least one letter and one digit.

Emailed links carry the token in the URL fragment (`/reset-password.html#token=…`), so it never reaches server logs or `Referer` headers. Only a SHA-256 hash of each token is stored, and issuing a new link invalidates older ones.

When `REQUIRE_EMAIL_VERIFICATION=true`, members who have not confirmed their email get 403 on the endpoints that reach other people: `POST /items`, `POST /items/{id}/images`, `POST /offers`, `POST /donation-requests`, `POST /conversations`, `POST /conversations/{id}/messages` and `POST /reports`. Browsing, profile and settings keep working.

## Users

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/users/me` | user | Own profile `{id, email, displayName, bio, location, latitude, longitude, avatarUrl, role, emailVerified, createdAt}` |
| PUT | `/users/me` | user | `{displayName, bio?, location?, latitude?, longitude?}`. The home location is private and used for `near me` defaults |
| POST | `/users/me/avatar` | user | multipart `file` (JPEG/PNG/GIF/WebP, ≤ 5 MB) |
| DELETE | `/users/me/avatar` | user | Remove avatar |
| POST | `/users/me/password` | user | `{currentPassword, newPassword}` → 204; revokes other sessions, refreshes the cookie and emails a security alert |
| POST | `/users/me/sign-out-everywhere` | user | Revokes all tokens → 204 |
| POST | `/users/me/verification-email` | user | Sends a new verification link → 202 (409 if already verified) |
| GET | `/users/me/export` | user | Downloads everything Swapp holds about the member as `swapp-data-<date>.json` (profile, listings, offers, exchanges, requests, conversations, notifications). Limited to 5 per hour |
| DELETE | `/users/me` | user | `{password}` → 204 and clears the cookie. Erases personal data, removes listings and photos, closes open offers and requests (notifying the other members) and revokes every session. 400 on a wrong password; 409 while an exchange or hand-over is in progress |
| GET | `/users/me/summary` | user | Badge counts `{unreadNotifications, unreadMessages, pendingIncomingOffers, pendingOutgoingOffers, activeExchanges, pendingDonationRequests, activeListings}` |
| GET | `/users/me/items?status=&page=&size=` | user | Own listings (all statuses except removed) |
| GET | `/users/me/offerable-items` | user | Own available exchange listings, used by the offer dialog |
| GET | `/users/{id}` | public | Public profile `{id, displayName, bio, location, avatarUrl, memberSince, activeListings, completedExchanges, itemsGivenAway}` (no email) |

## Categories, stats and files

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/categories` | public | `[{id, name, slug, icon}]` |
| GET | `/stats` | public | `{members, activeListings, completedSwaps}` |
| GET | `/site` | public | `{contactEmail, termsVersion, emailVerificationRequired}`, used by the Privacy Policy, Terms and Safety pages |
| GET | `/files/{key}` | public | Image bytes (immutable, long-cache) |

## Items

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/items` | public | Search available listings. Query: `q`, `categoryId`, `condition` (repeatable), `type` (repeatable: `EXCHANGE`, `DONATION`, `GIVEAWAY`, `REQUEST`), `location` (area name contains), `ownerId`, `excludeMine`, `postedWithinDays` (1-365), `withPhotos`, `lat` + `lng` (origin), `radiusKm` (0.1-200, needs an origin), `sort` (`newest`/`oldest`/`distance`), `page`, `size`. With an origin, each result includes `distanceKm` |
| GET | `/items/{id}` | public | Detail incl. `images[]`, `hasCoordinates` (exact `latitude`/`longitude` only for the owner) and `viewer` state (`owner`, `pendingOfferId`, `donationRequestStatus`, counts of pending offers/requests for owners) |
| POST | `/items` | user | `{title, description, categoryId, condition, listingType, location, latitude?, longitude?}` → 201. Coordinates are optional (both or neither) and stored rounded to about 110 m |
| PUT | `/items/{id}` | owner | Same body. Changing the listing type closes pending offers and requests |
| PATCH | `/items/{id}/status` | owner | `{status: AVAILABLE | UNAVAILABLE}`. Hiding closes pending offers and requests |
| DELETE | `/items/{id}` | owner/admin | Soft delete → 204 (409 if reserved) |
| POST | `/items/{id}/images` | owner | multipart `files` (≤ 6 per listing, ≤ 5 MB each) |
| DELETE | `/items/{id}/images/{imageId}` | owner | Remove an image |
| PUT | `/items/{id}/images/order` | owner | `{imageIds: [...]}`. The first image becomes the cover |

Enums. Condition: `NEW`, `LIKE_NEW`, `GOOD`, `FAIR`, `FOR_PARTS`. Status: `AVAILABLE`, `RESERVED`, `UNAVAILABLE`, `COMPLETED`, `REMOVED`.

## Offers

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| POST | `/offers` | user | `{requestedItemId, offeredItemId, message?}` → 201 |
| GET | `/offers/incoming?status=` | user | Offers on your listings |
| GET | `/offers/outgoing?status=` | user | Offers you made |
| GET | `/offers/{id}` | participant | Offer detail |
| POST | `/offers/{id}/accept` | recipient | Reserves both items, creates the exchange, closes conflicting offers → offer with `exchangeId` |
| POST | `/offers/{id}/reject` | recipient | |
| POST | `/offers/{id}/cancel` | offerer | Only while pending |

Offer shape: `{id, requestedItem, offeredItem, offerer, recipient, message, status, resolutionNote, exchangeId, createdAt, updatedAt}`. Status: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`.

Typical errors: 400 for your own item or a wrong listing type; 403 if it isn't your item or your offer; 409 if the item is unavailable, the offer is a duplicate or no longer pending.

## Exchanges

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/exchanges?status=` | user | Your exchanges (`ACTIVE`, `COMPLETED`, `CANCELLED`) |
| GET | `/exchanges/{id}` | participant | Detail |
| POST | `/exchanges/{id}/confirm` | participant | Records your confirmation; completes the exchange when both have confirmed |
| POST | `/exchanges/{id}/cancel` | participant | Cancels an active exchange and makes both items available again |

Shape: `{id, offerId, status, owner, requester, ownerItem, requesterItem, ownerConfirmedAt, requesterConfirmedAt, completedAt, cancelledAt, cancelledBy, createdAt, updatedAt}`.

## Donation and giveaway requests

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| POST | `/donation-requests` | user | `{itemId, message?}` for `DONATION`/`GIVEAWAY` listings → 201 |
| GET | `/donation-requests/incoming?status=` | user | Requests for your items |
| GET | `/donation-requests/outgoing?status=` | user | Your requests |
| POST | `/donation-requests/{id}/accept` | owner | Reserves the item and declines other pending requests |
| POST | `/donation-requests/{id}/decline` | owner | |
| POST | `/donation-requests/{id}/cancel` | requester (pending/accepted) or owner (accepted) | Releases the item if it was reserved |
| POST | `/donation-requests/{id}/complete` | owner | Marks the item handed over |

Status: `PENDING`, `ACCEPTED`, `DECLINED`, `CANCELLED`, `COMPLETED`.

## Notifications

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/notifications?unreadOnly=&page=&size=` | user | `{id, type, title, body, link, read, createdAt}` |
| GET | `/notifications/unread-count` | user | `{count}` |
| POST | `/notifications/{id}/read` | user | |
| POST | `/notifications/read-all` | user | `{updated}` |

Types: `OFFER_RECEIVED`, `OFFER_ACCEPTED`, `OFFER_REJECTED`, `OFFER_CANCELLED`, `EXCHANGE_CONFIRMED`, `EXCHANGE_COMPLETED`, `EXCHANGE_CANCELLED`, `DONATION_REQUESTED`, `DONATION_ACCEPTED`, `DONATION_DECLINED`, `DONATION_CANCELLED`, `DONATION_COMPLETED`.

## Messaging

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| GET | `/conversations` | user | `{id, otherUser, lastMessage, unreadCount, lastMessageAt}` |
| POST | `/conversations` | user | `{recipientId, itemId?, body}`: reuses an existing conversation with that user → 201 |
| GET | `/conversations/unread-count` | user | `{count}` |
| GET | `/conversations/{id}` | participant | |
| GET | `/conversations/{id}/messages?before=&size=` | participant | Oldest-first batch; pass `before` (a message id) to page back |
| POST | `/conversations/{id}/messages` | participant | `{body, itemId?}` → 201 |
| POST | `/conversations/{id}/read` | participant | Marks incoming messages read `{updated}` |

Non-participants receive 404.

## Safety: reports and blocks

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| POST | `/reports` | verified user | `{targetType: USER\|ITEM\|MESSAGE, targetId, reason, details?}` → 201 `{id, status, createdAt}`. Reasons: `SCAM, PROHIBITED_ITEM, FAKE_LISTING, HARASSMENT, SPAM, INAPPROPRIATE, OTHER`. A snapshot of the listing, profile or message is stored as evidence. Messages can be reported only by a conversation participant (others get 404), and only messages from the other person. 409 for a duplicate open report or more than 20 open reports; 400 when reporting yourself |
| POST | `/users/{id}/block` | user | Blocks a member → 204 (idempotent) |
| DELETE | `/users/{id}/block` | user | Unblocks → 204 |
| GET | `/users/me/blocks` | user | Members you have blocked `[{id, displayName, avatarUrl, location}]` |

A block works both ways: neither member can message the other, make an offer on the other's listing or request the other's giveaway (403). `GET /users/{id}` includes `blockedByMe` for signed-in viewers.

## Moderation (ADMIN only)

| Method | Path | Description |
| --- | --- | --- |
| GET | `/admin/summary` | `{openReports, suspendedMembers}` |
| GET | `/admin/reports?status=OPEN\|RESOLVED&page=&size=` | Report queue (open: oldest first; resolved: newest first) with evidence, the reported member's open-report count and the listing's current status |
| POST | `/admin/reports/{id}/resolve` | `{action: DISMISS\|REMOVE_ITEM\|SUSPEND_USER, note?}`. `REMOVE_ITEM` takes the listing down (409 while it's in an exchange or hand-over in progress) and resolves every open report about it. `SUSPEND_USER` needs a `note` (shown to the member) and resolves every open report about them. The reporter is notified of the outcome in general terms |
| GET | `/admin/users/suspended` | Suspended members with reasons |
| POST | `/admin/users/{id}/suspend` | `{reason}` → suspends directly (not moderators or yourself) |
| POST | `/admin/users/{id}/reinstate` | Lifts a suspension → 204 |

Suspending a member cancels their exchanges and hand-overs in progress, so nobody goes to meet them, and notifies the other side. It also closes their open offers and requests, hides their listings, revokes every session and emails them the reason. A suspended member's sign-in gets 403 with the reason, but only after a correct password. To make someone a moderator, run `update users set role = 'ADMIN' where email = '…';` against the database.

## WebSocket

`GET /ws` (upgrade). It is authenticated with the same cookie, and the server pushes only:

```json
{ "type": "notification",  "data": { NotificationDto } }
{ "type": "message",       "data": { MessageDto } }
{ "type": "messages-read", "data": { "conversationId": 12 } }
```

## Health

`GET /actuator/health` → `{"status":"UP"}` (liveness at `/actuator/health/liveness`, readiness at `/actuator/health/readiness`).
