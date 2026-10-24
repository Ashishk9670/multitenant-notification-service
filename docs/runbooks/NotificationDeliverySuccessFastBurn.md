# NotificationDeliverySuccessFastBurn

**Severity:** critical (page) | **SLO:** 99% of terminal deliveries succeed (30d)

Fires when more than 14.4% of terminal deliveries (SENT vs dead-lettered) failed over both the last 5 minutes and the last hour.
At that rate the monthly error budget is consumed in roughly two days.

## Impact
Notifications are being accepted (202) but exhaust their retries and land in `notifications.dlq`. End users are not receiving messages.

## Triage
1. Which tenant/channel? `topk(5, sum by (tenant, channel) (increase(notifications_dlq_total[30m])))` in Prometheus, or the *Notification overview* Grafana dashboard.
2. One channel only: that provider is failing. Check the circuit breaker (`resilience4j_circuitbreaker_state{name="sms"}`) and provider status pages. An open breaker means failures are being short-circuited and retried.
3. One tenant only: likely bad recipients or credentials. Look at the `lastError` of a DLQ event, or the delivery rows in Postgres (`deliveries` table, status `DEAD`).
4. Everything failing: check the app logs in Grafana > Explore > Loki (`{service="app"} |= "exhausted retries"`) and follow a `traceId` link into Tempo.

## Mitigation
* Provider outage: switch the channel provider via configuration (`EMAIL_PROVIDER`, `SMS_PROVIDER`, `PUSH_PROVIDER`) and restart, or wait for recovery; retries use exponential backoff.
* Bad deploy: roll back the release.
* After the cause is fixed, re-drive affected notifications by re-submitting them (the DLQ is not auto-replayed).

## Escalation
If not mitigated within 30 minutes, escalate to the service owner and notify affected tenants.
