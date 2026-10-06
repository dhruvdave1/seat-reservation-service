# WRITEUP

> **Draft written by Claude (AI) at the author's request, from the code, tests and measured runs in this repo. The author must rewrite it in their own words before submission (PLAN section 10). Every number here comes from a recorded run; see "Measurements".**

## 1. The atomic mechanism and why it is race-free

A reservation is one Postgres transaction at READ COMMITTED (`ReservationService.reserve`):

1. **Claim the idempotency key** (if any): `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING`.
2. **Lock the requested seats that are still available, in label order**:
   `SELECT label FROM seats WHERE show_id = ? AND label = ANY(?) AND status = 'available' ORDER BY label COLLATE "C" FOR UPDATE`.
   If fewer rows come back than labels requested, the request is declined before anything durable is written.
3. **Add to the user's per-show seat count, only within the limit** (conditional upsert, see 5).
4. **Insert the reservation**, priced by the database: `INSERT ... SELECT price_paise * n FROM shows ... RETURNING amount_paise`.
5. **Confirm the seats**: `UPDATE seats SET status = 'confirmed', reservation_id = ? WHERE ... AND status = 'available'`, and check the row count equals the number of seats.
6. Store the response on the idempotency key; commit.

Why two requests can never both get a seat:

- The seat row lock is the single point of serialization. Two transactions that want the same available seat cannot both hold its lock; the second waits.
- When the first commits, Postgres re-evaluates the second's `WHERE status = 'available'` against the new committed row version (READ COMMITTED's re-check after a lock wait). It no longer matches, so the second transaction gets fewer rows than requested and declines (409 `seat_taken`).
- There is no read-then-write: the check (`status = 'available'`) and the lock happen in the same statement, and the write is guarded by the same condition.
- Readers never see partial state: MVCC shows them either the state before the commit or after it.

All-or-nothing for multi-seat requests: any shortfall throws, and the whole transaction (key claim, count, reservation, seats) rolls back. A declined request leaves no trace (`ReserveTests.allOrNothingLeavesNoTraceOnDecline`).

## 2. Deadlock avoidance for multi-seat requests

Every transaction takes its locks in one global order: idempotency key, then seats sorted by label (byte order, `COLLATE "C"`), then the user's count row. In the lock query the `LockRows` step runs above the `Sort` (checked with `EXPLAIN` for small and large requests), so rows are locked in sorted order regardless of the plan the planner picks. With one total order there is no cycle, so no deadlock.

Evidence: the test `overlappingMultiSeatRequestsNeverDeadlockOrDoubleBook` fires 400 overlapping 2-3 seat requests in shuffled order on 4 seats, 5 rounds. Mutating the service to lock seats one at a time in request order made the same test fail with 758 `deadlock detected` errors; the real code produces none.

Safety net: `TransientRetry` re-runs the whole transaction up to 3 times on Postgres 40P01/40001 (Spring `ConcurrencyFailureException`), with 5-50 ms jitter. It is bounded: a persistent failure is a real 500, never disguised as a 409.

A plain multi-row `UPDATE ... WHERE label = ANY(...)` was considered and rejected: it locks rows in whatever order the plan visits them (index order or physical order), so two different plans can lock in opposite orders.

## 3. Idempotency storage and exactly-once

Table `idempotency_keys(user_id, idempotency_key, request_hash, reservation_id, response)`, primary key `(user_id, idempotency_key)`: keys are scoped per user.

- The key row is inserted at the start of the reserve transaction. A concurrent duplicate blocks on the primary key until the first transaction ends.
- If the first committed, the duplicate's insert conflicts; it reads the stored row and replays the original 201 body verbatim.
- If the first rolled back (a decline), the duplicate's insert succeeds and it runs as a fresh request.
- The response is written in the same transaction as the reservation, so a committed key always has its result (CHECK enforces `reservation_id` and `response` set together).

Exactly-once: 30 concurrent identical requests produce one reservation and 29 replays (`IdempotencyTests.concurrentDuplicatesReserveExactlyOnce`). Disabling the replay check made 4 of 6 idempotency tests fail.

Decision: declines are not stored. Consequence: a retried decline is evaluated again (it may succeed if the seat was freed), and "same key, different body" is detected only after a success. Storing declines would need the key row to survive the rollback of the seat work (a separate transaction), which adds a failure mode between the two transactions.

## 4. Same key, different body

`request_hash` is SHA-256 of the show id plus the sorted, normalized labels. On a key conflict with a different hash the service returns 409 `idempotency_mismatch` and writes nothing. Seat order does not matter (`["A2","A1"]` is the same request as `["A1","A2"]`); another show with the same key is a different request. The key may come from the `Idempotency-Key` header or the `idempotency_key` body field; if both are present and differ, 400.

## 5. Per-user limit

`user_show_seats(show_id, user_id, seat_count, seat_limit)` with `CHECK (seat_count BETWEEN 0 AND seat_limit)`. After the seats are locked:

```sql
INSERT INTO user_show_seats (...) SELECT ... WHERE :n <= s.per_user_limit
ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = seat_count + EXCLUDED.seat_count
WHERE seat_count + EXCLUDED.seat_count <= seat_limit
```

Zero rows affected means over the limit (409 `per_user_limit`). The conflict path locks the user's row, so one user's parallel reserves apply one at a time. 10 parallel single-seat reserves on limit 4 give exactly 4 (test, repeated 3x, and in every burst run). Without the conditional `WHERE` the CHECK still capped the count, but as a constraint error (a 500); the tests caught that.

## 6. Holds and expiry

The service confirms on reserve and offers owner-only cancel instead of time-boxed holds. `held` exists in the schema and API but is always 0. Cancel (`POST /reservations/{id}/cancel`):

- `UPDATE reservations SET status = 'cancelled' WHERE id = ? AND user_id = ? AND status = 'confirmed'`: owner check and state check in one guarded write; the row lock serializes concurrent cancels (20 concurrent cancels succeed once).
- Seats are released by `reservation_id`, never by label, so a stale cancel can never free a seat that was re-booked by someone else (`secondCancelCannotResurrectAReBookedSeat`).
- The user's per-show count is decremented in the same transaction.

## 7. Consistency vs availability under partition

The service chooses consistency. Every decision is made inside Postgres; if the app cannot reach the database, it cannot reserve and returns errors rather than guessing. Readiness checks the database and fails closed (503), so a load balancer would stop routing to an instance that lost its database. There is one database and no cache in the decision path, so there is no split-brain in which two instances could each confirm the same seat.

## 8. What pages me at 2am

To be written by the author. Candidates from what the service exposes:

- Any 5xx on reserve (`http_server_requests_seconds_count{status=~"5.."}`): every decline should be a 4xx.
- `transaction_retries_total` rising: deadlocks or serialization failures that should not happen with the lock order.
- Readiness failing (database unreachable).
- `seats_available + seats_held + seats_confirmed` not equal to total seats (cannot happen by construction; if it does, data is corrupt).
- p99 latency or Hikari pending connections climbing (capacity, see Measurements).

## 9. AI usage: directed vs decided

To be written by the author. Facts for reference: the prompts and responses are logged in `AI_PROMPT_LOG.md` and `AI_RESPONSE_LOG.md`. The author wrote the plan, the V1 schema and the reserve SQL statements; Claude reviewed them, suggested the composite foreign key, the lock-first order and the `SELECT ... FOR UPDATE` ordering, and at the author's explicit request wrote the reserve transaction body, idempotency, per-user limit, cancel, observability, the burst script and this draft. Commit messages say which code was AI-written.

## 10. What I would do next

- Transactional outbox for downstream events (payment, notifications), instead of a broker in the decision path.
- Time-boxed holds with expiry (status `held` plus `held_until`, released by a conditional update), if the product needs a payment step.
- A paid instance or more CPU; see Measurements for why the free tier is the bottleneck.
- Rate limiting per user at the edge, so one client cannot consume the whole connection pool.
- Store declines under the idempotency key, if strict exactly-once for declines is required.

## Measurements

From real runs only; full tables in README "Measured runs".

- Live on Render free + Neon: 2,000 requests at concurrency 100 on one seat: 30 req/s, p50 3.2 s, exactly one 201, zero 5xx, every reconciliation check passed.
- Live, 20,000 at concurrency 500: 27 req/s; the app returned no 5xx and every answer it gave was correct, but Render's proxy returned 338 502s and 91 requests timed out before reaching the app. The bottleneck is CPU (a fraction of a core), not correctness.
- Locally at 0.1 CPU, the first configuration (unbounded virtual threads) produced 262 pool-timeout 500s; capping concurrent requests at the pool size (10) removed them. That is the shipped configuration.
