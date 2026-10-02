# Multi-Tenant Notification Service

Spring Boot 3 (Java 21, Maven) service that ingests notification requests over REST, validates and
rate-limits them per tenant, and delivers them through Kafka to email / SMS / push channel consumers
with priority-weighted consumption, retries with exponential backoff and a dead-letter topic.

## Architecture

```
 client --X-Tenant-Id, Idempotency-Key--> REST API
            validate -> render template (PostgreSQL) -> dedupe (Redis SETNX)
                     -> rate limit (Redis token bucket) -> daily quota (Redis counter)
                                   |
                                   v
         notifications.high (6 thr) / .normal (3) / .low (1)       <- priority topics, weighted consumers
                                   |  PriorityRouter: one DeliveryEvent per channel
                                   v
   notifications.channel.email | .sms | .push  -> ChannelConsumers -> NotificationSender (log stubs)
                                   | failure
                                   v
        notifications.retry.1 .. N  (header x-not-before = now + backoff(n))
                                   | retry consumer waits, re-publishes to channel topic
                                   v   after max retries
                          notifications.dlq
```

* **Priority**: three topics; partitions and listener threads follow the weights `6:3:1`
  (`notification.consumers.*`), so HIGH traffic is never starved by LOW bulk traffic.
* **Dedupe**: optional `Idempotency-Key` header, stored in Redis with `SET NX EX`, scoped per tenant.
  Replays return 200 with the original id (`duplicate: true`). The key is released if the request is later rejected.
* **Rate limiting**: per-tenant token bucket executed atomically in Redis (Lua); 429 + `Retry-After`.
  Per-tenant overrides under `notification.rate-limit.tenants.<tenant>`.
* **Quotas**: per-tenant daily counter in Redis (UTC), overrides under `notification.quota.tenants.<tenant>`.
* **Retry**: `delay(n) = min(max, initial * multiplier^(n-1)) +/- jitter`; one retry topic per attempt, then DLQ.
* **Templates**: CRUD at `/api/v1/templates`, stored in PostgreSQL (JPA + Flyway); `{{var}}` and `{{var|default}}`.
* **Delivery status**: every delivery is tracked in the `deliveries` table (ACCEPTED, SENT, RETRYING, DEAD).
* **Stub providers**: log-only senders. A recipient containing `fail` always fails; `flaky` fails twice then succeeds.

Contracts: [`asyncapi.yaml`](asyncapi.yaml) (Kafka events) and OpenAPI at `/v3/api-docs` / `/swagger-ui.html`.

## Run

```bash
docker compose up -d --wait        # Kafka, Redis, Postgres
mvn spring-boot:run                # http://localhost:8080  (admin UI at /)
```

```bash
curl -X POST localhost:8080/api/v1/templates -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' \
  -d '{"name":"welcome","channel":"EMAIL","subject":"Hi {{name}}","body":"Welcome {{name}}!"}'

curl -X POST localhost:8080/api/v1/notifications -H 'X-Tenant-Id: acme' -H 'Idempotency-Key: order-1' \
  -H 'Content-Type: application/json' \
  -d '{"channels":["EMAIL"],"priority":"HIGH","recipients":{"email":"a@b.com"},"templateName":"welcome","variables":{"name":"Ann"}}'

curl -H 'X-Tenant-Id: acme' localhost:8080/api/v1/deliveries
```

The admin UI (`/`) manages templates, sends notifications and shows delivery status for the chosen tenant.

## Tests

| Layer | Command | Needs |
|---|---|---|
| Unit (JUnit 5, Mockito, MockMvc): rate limiter, backoff, rendering, ingest service, consumers/retry/DLQ, controllers | `mvn test` | nothing |
| Integration + API (Testcontainers Kafka/Redis/Postgres, RestAssured): dedupe, rate limit, quota, retry, DLQ, templates | `mvn verify` | Docker |
| E2E UI (Playwright, `e2e/`) | `make e2e` or `./scripts/e2e.sh` | Docker, Node 20+ |

`*IT` classes are skipped automatically when Docker is unavailable. Coverage (JaCoCo): `target/site/jacoco/index.html`
after `mvn verify`. CI (`.github/workflows/ci.yml`) runs unit, integration and Playwright jobs and uploads the reports.

Note: on JDK 25+ the build passes `-Dnet.bytebuddy.experimental=true` to Mockito's ByteBuddy; JDK 21 is the target.

## Notes / limitations

* The retry consumer blocks until a message is due (simple and ordered; keep `max-delay` below `max.poll.interval.ms`).
* Notification ids are not persisted at ingest time; delivery rows appear once the router consumes the event.
* No authentication: tenant identity comes from the `X-Tenant-Id` header; put an API gateway in front for real use.
