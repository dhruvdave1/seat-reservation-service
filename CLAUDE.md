# Seat Reservation Service - Paytm take-home

Read docs/PLAN.md first. It holds the assignment, all decisions, the draft schema, the reserve flow and the build phases.
Also read AI_PROMPT_LOG.md for how the work has been directed so far.

## Rules
- Java 25, Spring Boot 4.1.x, PostgreSQL, Maven.
- Hot-path SQL is explicit JdbcClient, not JPA. I must be able to explain every query.
- Flyway for schema. Never ddl-auto=update.
- Money is integer paise in long. Never float or double.
- No Kafka, no extra infrastructure.
- Package by feature: show, reservation, idempotency, auth, common.
- Conventional Commits. Small incremental commits. No squashing.
- Prefer plain hyphens, not em dashes, in docs.

## Non-negotiables from the spec
- No seat ever confirmed twice. One atomic conditional UPDATE, checked by row count.
- Zero 5xx under load. Declines are 4xx domain outcomes. Do not hide real failures as 409.
- available + held + confirmed == total_seats at all times.
- Idempotency: same key replays, same key with different body returns 409.
- Per-user limit holds under concurrency (DB-enforced).
- Identity from JWT claims only, never the request body.

## Working agreement
- I write the reserve transaction, idempotency logic, per-user limit and WRITEUP.md myself. Do not generate those unless I ask. Review my versions and try to break them under concurrency.
- Scaffolding, Dockerfile, compose, JWT wiring, logging filter, metrics, health endpoints, burst script and README skeleton are fine to generate.
- Be blunt. Point out when I am not following industry-standard practice.
- Do not invent metrics or results. Burst results must come from real runs.
