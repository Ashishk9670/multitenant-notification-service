# NotificationDlqRateHigh

**Severity:** warning | **SLO:** bounded dead-letter volume per tenant

Fires when a tenant/channel pair sends more than 20 deliveries to `notifications.dlq` in 15 minutes (sustained 5 minutes).

## Triage
1. Identify the pair from the alert labels (`tenant`, `channel`).
2. Read recent DLQ events: `kafka-console-consumer --topic notifications.dlq` (or look at `DEAD` rows in the `deliveries` table). `lastError` shows the final failure, for example provider 4xx (bad recipient) vs 5xx or timeouts (provider outage).
3. Check the channel circuit breaker state and the provider's status page.

## Mitigation
* Bad recipient data: tenant-side fix; nothing to do on the platform.
* Provider outage: wait or switch provider; DLQ entries are not replayed automatically, so re-submit once the provider is healthy.
* A tenant flooding failures can be throttled with `notification.rate-limit.tenants.<tenant>.*`.
