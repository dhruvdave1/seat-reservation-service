# Seat Reservation Service

Seat reservation API for the Paytm Money "Deploy & Observe" take-home. Java 25, Spring Boot 4.1, PostgreSQL, Flyway.

Live: https://seat-reservation-service-0ecy.onrender.com

## Run locally

Requires Docker.

```sh
docker compose up --build
curl localhost:8080/actuator/health/readiness
```

Tests (Testcontainers starts its own Postgres):

```sh
./mvnw verify
```

## Endpoints

| Path | Purpose |
|---|---|
| `GET /actuator/health/liveness` | Process is up. Never touches the DB. |
| `GET /actuator/health/readiness` | DB reachable. Returns 503 when it is not. |

API endpoints are added phase by phase; see `docs/PLAN.md`.

## Auth

Every request except health, `GET /shows/{id}` and `POST /auth/token` needs `Authorization: Bearer <token>`. The user id is the token's `sub` claim; any user id in a request body is ignored. Tokens are HS256 JWTs and expire after 1 hour.

`POST /auth/token` is a test-harness issuer: it signs a token for any `user_id` (`[A-Za-z0-9._@-]{1,64}`), so you can act as many users. Adding the admin key returns an admin token, which `POST /shows` requires.

```sh
# user token
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
# admin token
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"ops","admin_key":"<ADMIN_KEY>"}'
# -> {"access_token":"...","token_type":"Bearer","expires_in":3600}
```

| Case | Status |
|---|---|
| No or invalid/expired token | 401 |
| Valid token without the admin role on `POST /shows` | 403 |
| Wrong admin key on `/auth/token` | 403 |

Required environment: `JWT_SECRET` (at least 32 bytes) and `ADMIN_KEY`. The app refuses to start without them.

## Deployment

- App: Render free web service (Docker runtime), region Ohio (us-east-2).
- DB: Neon Postgres, region us-east-2, direct (non-pooled) endpoint. The app pools with Hikari.
- Config: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` set as Render environment variables. No credentials in the repo.

### Cold start

Render's free tier stops the instance after 15 minutes without inbound traffic and gives it 0.1 CPU. A measured cold start (2026-10-02, first request after a spin-down, readiness endpoint) took 84.8 s end to end:

| Stage | Time |
|---|---|
| Render wakes the container | ~6 s |
| JVM and Spring startup (CPU-bound) | ~65 s |
| First Neon connection (Neon compute resuming) | ~5 s |
| Remaining startup and first request | ~9 s |

Mitigations:

- The image ships a JDK 25 AOT cache built by a training run at image build time, to cut class loading and linking at startup. On Render this took Spring startup from 71.7 s to 40.3 s (`Started ... in` log line, 2026-10-05). End-to-end cold start with the cache is not yet re-measured.
- An external monitor calls `/actuator/health/liveness` every 10 minutes so the instance does not spin down. Liveness does not touch the DB, so Neon may still suspend and costs ~5 s on the first DB request after it does.

The service still survives a cold start without the keep-warm ping: it comes back on its own and returns 200; it is just slow.
