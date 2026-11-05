# NotificationApiLatencyP95High

**Severity:** warning | **SLO:** p95 of `/api/**` requests under 500 ms

Fires when `histogram_quantile(0.95, http_server_requests_seconds_bucket{uri=~"/api/.*"})` stays above 0.5 s for 10 minutes.

## Triage
1. Which endpoint? Use the *HTTP p95 latency (API)* panel (split by `uri`).
2. Ingest path is `idempotency (Redis) -> rate limit (Redis) -> quota (Redis) -> Kafka publish (acks=all)`. Slow Redis or Kafka shows up here first. Check broker health and `redis-cli --latency`.
3. Look at a slow trace in Grafana > Explore > Tempo (search by `http.server.requests` duration); the span breakdown shows whether time is spent in Redis, Kafka send or Postgres.
4. Check JVM pressure: GC pauses and heap on the overview dashboard, CPU throttling in the container.

## Mitigation
Scale out the app, fix the slow dependency, or reduce load by lowering tenant rate limits. If caused by a deploy, roll back.
