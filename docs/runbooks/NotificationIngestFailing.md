# NotificationIngestFailing

**Severity:** critical | **SLO:** availability of notification ingest

Fires when more than 5% of ingest attempts are rejected with reason `publish_failed` or `dependency_unavailable` for 3 minutes. The API fails closed: when Kafka or Redis is unreachable it returns **503** and does not accept the notification, so nothing accepted is lost; clients must retry (re-using the same `Idempotency-Key`).

## Triage
1. Which dependency? Check `/actuator/health` (redis details) and the app logs: Redis errors (`RedisConnectionFailureException`, `QueryTimeoutException`) mean `dependency_unavailable` / `publish_failed` from the rate limiter or quota; Kafka timeouts (`TimeoutException ... send`) mean the broker is unreachable or leaderless.
2. Redis: `redis-cli ping`, memory (`INFO memory`), connection limits, network policy changes.
3. Kafka: broker up, `kafka-topics --describe` for under-replicated or offline partitions, disk space.

## Recovery
The service recovers by itself when the dependency returns (verified by the chaos integration test `ChaosIT`: no restart needed). Confirm `notifications_accepted_total` increases again and the 503 rate returns to zero. Notifications accepted before the outage are still delivered.
