# Seat Reservation Service

Seat reservation API for the Paytm Money "Deploy & Observe" take-home. Java 25, Spring Boot 4.1, PostgreSQL (Neon), Flyway, plain SQL through `JdbcClient`.

Live: https://seat-reservation-service-0ecy.onrender.com

How it stays correct under concurrency (atomic mechanism, lock order, idempotency, limits, cancel): see [WRITEUP.md](WRITEUP.md). Plan and decisions: [docs/PLAN.md](docs/PLAN.md).

## Run locally

Requires Docker.

```sh
docker compose up --build
curl localhost:8080/actuator/health/readiness
```

Tests (Testcontainers starts its own Postgres 18; includes the concurrency tests):

```sh
./mvnw verify
```

## API

All JSON is snake_case. Money is integer paise. Errors are RFC 9457 problem details; declines carry a `reason`.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /auth/token` | public | Issue a JWT, see Auth |
| `POST /shows` | admin | `{name, price_paise, seats[], per_user_limit?}` -> 201 show, all seats available. `per_user_limit` defaults to 4 |
| `GET /shows/{id}` | public | Per-seat status and `counts` (available/held/confirmed) from one snapshot |
| `POST /shows/{id}/reserve` | user | `{seats[], idempotency_key?}` (key also accepted as `Idempotency-Key` header) -> 201 `{reservation_id, show_id, user_id, seats, amount_paise, status}` |
| `POST /reservations/{id}/cancel` | owner | 200, seats released and re-bookable |
| `GET /actuator/health/liveness` | public | Process up; never touches the DB |
| `GET /actuator/health/readiness` | public | DB reachable; 503 when not (fails closed) |
| `GET /actuator/prometheus` | public | Prometheus metrics |

Reserve is all-or-nothing: if any requested seat is unavailable, nothing is reserved.

| Outcome | Status | `reason` |
|---|---|---|
| Reserved | 201 | |
| Same idempotency key, same seats | 201, original body replayed | |
| A seat is taken | 409 | `seat_taken` |
| Over the per-user limit for the show | 409 | `per_user_limit` |
| Same idempotency key, different seats or show | 409 | `idempotency_mismatch` |
| Label is not a seat of the show | 400 | `unknown_seat` |
| Duplicate labels, bad body, bad key | 400 | |
| Unknown show | 404 | |
| Cancel someone else's reservation | 403 | `not_owner` |
| Cancel twice | 409 | `already_cancelled` |
| No or invalid token | 401 | |

Example:

```sh
URL=https://seat-reservation-service-0ecy.onrender.com
TOKEN=$(curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -X POST $URL/shows/$SHOW_ID/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"seats":["A12","A13"],"idempotency_key":"order-1"}'
```

## Auth

Every request except health, metrics, `GET /shows/{id}` and `POST /auth/token` needs `Authorization: Bearer <token>`. The user id is the token's `sub` claim; a user id in a request body is ignored. Tokens are HS256 JWTs, valid 1 hour.

`POST /auth/token` is a test-harness issuer: it signs a token for any `user_id` (`[A-Za-z0-9._@-]{1,64}`), so you can act as many users. With the admin key it returns an admin token, which `POST /shows` requires. In production an identity provider would issue tokens and this endpoint would not exist.

```sh
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"ops","admin_key":"<ADMIN_KEY>"}'
# -> {"access_token":"...","token_type":"Bearer","expires_in":3600}
```

Required environment: `JWT_SECRET` (at least 32 bytes) and `ADMIN_KEY`. The app refuses to start without them.

## Burst test

One command; fires a hot-seat storm, then reconciles everything it can check from outside:

```sh
BASE_URL=https://seat-reservation-service-0ecy.onrender.com ADMIN_KEY=... scripts/burst.sh --requests 20000 --concurrency 500
# without the admin key, against an existing show:
BASE_URL=... scripts/burst.sh --show-id <id> --requests 20000
```

It uses a local JDK 25 if present, otherwise the `eclipse-temurin:25-jdk` image. Options: `--users`, `--seats`, `--hot`, `--hot-share`, `--dup-share` (requests re-sent with the same idempotency key), `--cancel-after`.

What it does: creates a show (or uses `--show-id`), mints a token per user, checks the per-user limit with 10 parallel reserves by one user, then fires the burst (80% of requests at 5 hot seats, 10% two-seat requests, 10% duplicated with the same key). It prints the outcome distribution and latency, then checks: zero 5xx, zero transport errors, `available + held + confirmed == total_seats`, no seat in two reservations, confirmed seats equal the seats in distinct 201s, a key never yields two reservations, no user above the limit, and that the Prometheus counters and gauges moved by exactly what the API returned. Exit code 0 only if all pass.

### Burst results

See "Measured runs" below; every number there is from a real run.

## Observability

Metrics (`/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | Committed reservations (replays excluded) |
| `reservations_declined_total{reason}` | `seat_taken`, `per_user_limit`, `idempotent_replay`, `unknown_seat`, `unknown_show`, `idempotency_mismatch` |
| `reservations_cancelled_total` | Owner cancels |
| `seats_available`, `seats_held`, `seats_confirmed` | Gauges read from the DB in one statement; reconcile with `GET /shows/{id}` |
| `transaction_retries_total` | Transactions re-run after a deadlock or serialization failure |
| `http_server_requests_seconds_*` | Per-endpoint latency and status codes (Spring) |

Counters move only after the outcome is final. They reset when the process restarts; the gauges do not, because they come from the database.

Logs are one JSON object per line (ECS format) on stdout, visible in Render's log view. Every line during a request carries `request_id`: taken from an incoming `X-Request-Id` header or generated, and echoed back in the `X-Request-Id` response header. Each request ends with an access line (`method`, `path`, `status`, `duration_ms`); reserve and cancel add a line with `outcome`, `show_id`, `user_id`, `reservation_id`.

## Deployment

- App: Render free web service (Docker runtime, 512 MB, 0.1 CPU), region Ohio (us-east-2).
- DB: Neon Postgres 18, us-east-2, direct (non-pooled) endpoint; the app pools with Hikari (10 connections).
- Config: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `ADMIN_KEY` as Render environment variables. No credentials in the repo.
- Schema: Flyway migrations `V1` (shows, seats, reservations), `V2` (idempotency keys), `V3` (per-user limit), applied on startup.
- CI: GitHub Actions runs `./mvnw verify` (all tests against Postgres 18) on every push.

### Cold start

Render's free tier stops the instance after 15 minutes without inbound traffic. A measured cold start (2026-10-02, first request after a spin-down, readiness endpoint) took 84.8 s end to end:

| Stage | Time |
|---|---|
| Render wakes the container | ~6 s |
| JVM and Spring startup (CPU-bound) | ~65 s |
| First Neon connection (Neon compute resuming) | ~5 s |
| Remaining startup and first request | ~9 s |

Mitigations:

- The image ships a JDK 25 AOT cache built by a training run at image build time. On Render this took Spring startup from 71.7 s to 40.3 s (`Started ... in` log line, 2026-10-05). End-to-end cold start with the cache is not re-measured.
- An external monitor calls `/actuator/health/liveness` every 10 minutes so the instance does not spin down. Liveness does not touch the DB, so Neon may still suspend and costs ~5 s on the first DB request after it does.

The service survives a cold start without the ping: it comes back on its own and returns 200; it is slow.

## Measured runs

All numbers below are from real runs of `scripts/burst.sh`; none are estimates.

**Live, Render free tier + Neon** (2026-10-06, from India to Ohio; one-seat show, so every request fights for the same seat):

| Requests / concurrency | Throughput | p50 / p99 latency | 201 / 409 | 5xx | Reconciliation |
|---|---|---|---|---|---|
| 200 / 50 | 17 req/s | 2.7 s / 3.8 s | 1 / 199 | 0 | all checks pass |
| 2,000 / 100 | 30 req/s | 3.2 s / 4.9 s | 1 / 1,999 | 0 | all checks pass |
| 20,000 / 500 | 27 req/s | 4.1 s / 243 s | 1 / 19,570 | 338 x 502 from Render's edge (app: 0), see below | correctness checks pass |

The 20,000 / 500 run is the spec's scale, and it is where the free tier gives out. The app itself returned no 5xx: its own `http_server_requests` metrics for reserve show only 201, 409 and 400 since the process started, and its 409 count matches the client's exactly once the 21 requests the client gave up on are added. The 338 502s and 91 transport errors (timeouts, dropped connections) happened in front of the app, at Render's proxy, for requests that waited too long in the queue at 27 req/s. Everything the app did answer was correct: one winner, 19,591 `seat_taken`, the invariant held, no transaction retries.

**Local Docker, same image** (200-seat show, 5 hot seats, 10% two-seat requests, 10% duplicate sends; 20,000 requests at concurrency 500):

| App container | Throughput | p50 / p99 latency | 5xx | Reconciliation |
|---|---|---|---|---|
| No CPU limit | 3,369 req/s | 97 ms / 510 ms | 0 | all checks pass |
| 0.1 CPU, unbounded virtual threads (earlier config) | 13 req/s | 30 s / 120 s | 262, plus 10,141 client timeouts | correctness held, failed on 5xx |
| 0.1 CPU, 10 platform threads (shipped config) | 13 req/s | 39 s / 87 s | 0 | all checks pass |

What this shows:

- Correctness held in every run: never a double booking, the invariant held, and the Prometheus counters and gauges matched what the API returned.
- Throughput is CPU-bound. Each request costs a few milliseconds of CPU (JWT verification, JSON, logging); the free tier has a fraction of a CPU, so a 20,000-request storm queues and latency grows with the queue. Render's free instance measured about 2x faster than a Docker container limited to 0.1 CPU.
- The earlier configuration failed under load: unbounded concurrency starved the threads holding database connections, and waiters hit the 30 s pool timeout (500s). Capping concurrent requests at the pool size removed the 500s. That is the shipped configuration.
- With more CPU, raise `server.tomcat.threads.max` and `spring.datasource.hikari.maximum-pool-size` together. Zero 5xx at 20,000 / 500 needs either more CPU or a gentler client; it is not reachable by tuning on the free tier.
- Shedding load with a fast 429 was considered and not done: the spec expects exactly one 201 and 409 for everyone else on a hot seat, so a 429 would be a different wrong answer, not a fix.
- Burst client note: it uses HTTP/1.1. Over HTTP/2 the JDK client multiplexes everything onto one connection, Render's edge caps concurrent streams per connection (~100), and the client fails with "too many concurrent streams" before the server sees the requests. On macOS raise the open-files limit (`ulimit -n 8192`) for concurrency 500.
- Render's health check is set to `/actuator/health/liveness`: during a storm requests queue, and readiness (which needs a database connection) would be slow to answer.
