# Seat Reservation Service - Plan and Decisions

Handoff document. Written from the planning chat (see AI_PROMPT_LOG.md entries 1-12) so a fresh session has the full context. Status of every item is in "Status" at the bottom.

## 1. The assignment (Paytm Money, Deploy & Observe round)

Build, deploy and operate a seat reservation service. Graded on the running service: they clone it, deploy-check it, and hit the live URL with their own concurrency bursts (~20,000 concurrent reservations, many on the same hot seats, some retrying with the same key). AI use is allowed and must be disclosed honestly. They will ask me to extend the service live, so I must understand all of it.

API (JSON, money is integer paise, never float):
- POST /shows (admin): {name, seats[], price_paise}. Returns show with id, all seats "available".
- POST /shows/{id}/reserve (authenticated): body {seats[], idempotency_key} (key may be header or body). Identity from the token only. 201 {reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed"}.
- Cancel (owner only) OR time-boxed auto-expiring holds. A released seat must be cleanly re-bookable and must never resurrect a seat confirmed to someone else.
- GET /shows/{id}: per-seat status (available/held/confirmed) and counts. available + held + confirmed == total_seats at all times (the reconciliation invariant).
- Health and metrics.

Must hold under load:
1. No seat ever confirmed to two users. For each hot seat: exactly one 201, everyone else 409.
2. Zero 5xx across the burst. Declines are 4xx.
3. Invariant holds to the unit, during and after the burst.
4. Same idempotency key reserves exactly once (retry returns the original); same key with different seats returns 409.
5. Per-user limit (default 4) holds under concurrency: 10 parallel reserves on limit=4 ends with at most 4 held.
6. Identity is token-derived. Spoofed body user cannot act as another user; only the owner can cancel.
Partial requests (["A12","A13"] with one free): decide and document all-or-nothing vs best-effort, and make it hold under concurrency.

Deploy and observe (weighted equally with correctness): public URL on a free tier surviving a cold start; Dockerfile/compose; liveness endpoint plus readiness that checks the DB and fails closed; Prometheus metrics (confirmed counter, declined-by-reason counter with seat-taken / per-user-limit / idempotent-replay, seats-available gauge) that reconcile with API state; structured logs with correlation id; one-command burst script (hot-seat storm, prints outcome distribution and final reconciliation).

Deliverables: public Git repo with incremental history; live URL; burst script and README; metrics and logs access; WRITEUP.md (atomic mechanism and why race-free, deadlock avoidance for multi-seat, idempotency storage and exactly-once, same-key-different-body, holds and expiry, consistency vs availability under partition, what pages me at 2am, honest AI usage directed vs decided, what I would do next).

## 2. Decisions

| Area | Decision | Reason |
|---|---|---|
| Java | 25 (LTS) | Current LTS, supported by Boot 4 |
| Framework | Spring Boot 4.1.x (latest GA, not snapshot) | Boot has no LTS; 4.0 loses OSS support Dec 2026 |
| Build | Maven | Familiar |
| DB | PostgreSQL | Free hosted options (Neon etc.), ON CONFLICT and UPDATE...RETURNING fit the problem |
| Migrations | Flyway, ddl-auto=validate | Reproducible schema |
| Data access | JdbcClient with explicit SQL; no JPA, no Lombok | Must explain every hot-path statement live |
| Auth | Spring Security OAuth2 resource server, HS256 JWT, user id and role from claims only | Identity must be token-derived |
| Errors | RFC 9457 Problem Details | Built into Spring |
| Observability | Actuator + Micrometer Prometheus, built-in structured JSON logs, request id in MDC | Spec |
| Tests | JUnit 5 + Testcontainers Postgres, one real concurrency test | Prove the invariant |
| CI | GitHub Actions: build and test on push | Standard practice |
| Messaging | NO Kafka | Decision must be synchronous and atomic in the DB; no free Kafka hosting; transactional outbox goes in WRITEUP "next steps" only |
| Architecture | Package by feature (show, reservation, idempotency, auth, common), controller/service/repository inside; no separate adapter layer | Right size for one service and one DB |
| Pricing | One price_paise per show (spec shows one price). amount = price * seat count, stored on the reservation at reserve time | Snapshot; keep calc in one small method in case tiered pricing is asked live |
| Holds | Confirm on reserve, owner-only cancel flips seat back with a conditional update. "held" stays in the model but is always 0; document it | Simplest correct model |
| Git | Public repo, Conventional Commits, small incremental commits, NO squash merges | They read the history |

## 3. Draft schema (to be refined by me, written as Flyway migrations)

- shows(id, name, price_paise bigint CHECK > 0, per_user_limit default 4, total_seats)
- seats(show_id, label, status CHECK IN ('available','held','confirmed'), reservation_id, PK(show_id, label))
- reservations(id, show_id, user_id, status, amount_paise, created_at, cancelled_at)
- idempotency_keys(user_id, key, request_hash, reservation_id, response, PK(user_id, key))
- user_show_holds(show_id, user_id, held_count CHECK BETWEEN 0 AND limit, PK(show_id, user_id))

The CHECK on held_count makes the database itself enforce the per-user limit. Idempotency keys are scoped per user.

## 4. Reserve flow (one DB transaction)

1. INSERT idempotency row ON CONFLICT DO NOTHING. On conflict load the existing row: same request hash replays the stored response; different hash returns 409. (A concurrent duplicate blocks until the first transaction finishes.)
2. Conditionally increment user_show_holds. If it would exceed the limit, decline.
3. Conditional seat UPDATE per seat, in sorted label order (deterministic lock order, no deadlock): UPDATE seats SET status='confirmed', reservation_id=? WHERE show_id=? AND label=? AND status='available'. Row count 1 = won, 0 = taken. Any 0 rolls back the whole request (all-or-nothing).
4. INSERT reservation, store response on the idempotency row, commit.

Never read-then-write.

## 5. Open decisions I must make and defend

- Do declined outcomes get stored under the idempotency key? (If not, a retried decline can later succeed, and same-key-different-seats is only enforced after a success. If yes, the idempotency row must survive the rollback of seat work.)
- Zero 5xx without lying: retry transient Postgres errors (deadlock 40P01, serialization 40001) a bounded number of times, keep transactions short, size Hikari and Tomcat deliberately, test with 20k requests. Do not turn real failures into fake 409s.

## 6. API status codes

POST /shows 201 (admin only). Reserve: 201; replay returns the original 201 body; 409 for seat-taken, per-user-limit and idempotency mismatch; 400 bad input; 401/403 auth; 404 unknown show. Cancel: owner only. GET /shows/{id}: counts from one query for a consistent snapshot. Health: /actuator/health/liveness, /actuator/health/readiness (checks DB), /actuator/prometheus.

## 7. Build phases (commit at each)

0. Repo, Boot skeleton, Dockerfile, compose with Postgres, hello-world deployed with a DB connection (de-risk hosting first).
1. Flyway schema and POST /shows.
2. JWT auth and a documented /auth/token helper.
3. Reserve, single seat, atomic UPDATE.
4. Idempotency.
5. Per-user limit and multi-seat.
6. Cancel.
7. Health, metrics, structured logs.
8. burst script and concurrency test.
9. WRITEUP.md and README.

## 8. Hosting notes (verify current terms)

Free tiers change. Render free web services sleep when idle and free Postgres may expire; Neon for DB plus Render or Fly.io for the app is a common combination. Free instances have about 512 MB RAM: set -XX:MaxRAMPercentage and measure cold start.

## 9. Questions for the recruiter (Karan)

1. How will graders obtain auth tokens, and how is admin distinguished? Proposal: documented POST /auth/token issuing a JWT for a given user id, admin protected by a configured key.
2. Confirm one price per show (no per-seat pricing).
3. Seat counts or payload sizes they will test with?

## 10. Division of work

I write and can explain: reserve transaction, idempotency, per-user limit, cancel, error and retry handling, WRITEUP.md in my own words. AI may write: scaffolding, Dockerfile and compose, JWT wiring, request-id logging, metrics registration, health endpoints, burst script (which I must read and explain), README skeleton, deploy config. AI acts as adversary reviewing my concurrency code. Everything AI-generated is logged (AI_PROMPT_LOG.md, AI_RESPONSE_LOG.md).

## Status

- [x] Public repo created
- [x] Project generated from start.spring.io and building (./mvnw verify)
- [x] Hooks and logs committed
- [ ] Questions sent to recruiter
- [x] Phases 0-7 built, tested and deployed; phase 8 burst script written and run; phase 9 README and WRITEUP draft written

## Deviations from this plan (as built)

- Reserve flow order: claim idempotency key, then `SELECT ... ORDER BY label FOR UPDATE` on available seats (lock first, so declines write nothing), then the per-user count, then insert the reservation, then confirm seats. The reservation is inserted after the seat lock, not first.
- Per-user limit table is `user_show_seats(seat_count, seat_limit)`; the limit is copied into the row so a CHECK can enforce it.
- Declines are not stored under the idempotency key (section 5 decision): the key claim rolls back with the decline.
- Unknown seat labels are 400 `unknown_seat`, not 409.
- Cancel is `POST /reservations/{id}/cancel`; a second cancel is 409 `already_cancelled`.
- Under load on 0.1 CPU, unbounded virtual-thread concurrency caused pool-timeout 500s; requests now run on 10 platform threads, one per pooled connection (a semaphore bulkhead on virtual threads was tried and was worse). See README "Measured runs".
- The reserve transaction, idempotency, per-user limit, cancel and the WRITEUP draft were written by Claude at the author's explicit request (section 10 originally reserved them for the author).
