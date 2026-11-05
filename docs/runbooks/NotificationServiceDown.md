# NotificationServiceDown

**Severity:** critical

Prometheus has failed to scrape `up{job="notification-service"}` for 2 minutes.

## Triage
1. Is the process running? `docker compose ps app` / `kubectl get pods`; check restarts and `OOMKilled`.
2. Logs: Grafana > Explore > Loki `{service="app"}` or `docker compose logs app`. Startup failures are most often Postgres or Flyway migration problems.
3. Readiness: `curl /actuator/health/readiness`. A scrape failure with a healthy pod points at network policy or the Prometheus target configuration.

## Mitigation
Restart or roll back the deployment. Accepted notifications are durable in Kafka, so consumers resume from their committed offsets when the service is back.
