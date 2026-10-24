# NotificationDeliverySuccessSlowBurn

**Severity:** warning (ticket) | **SLO:** 99% of terminal deliveries succeed (30d)

Fires when more than 6% of terminal deliveries failed over both the last 30 minutes and the last 6 hours, for 15 minutes.
Lower urgency than the fast burn, but the budget will be exhausted within days if it persists.

## Triage
Same steps as [NotificationDeliverySuccessFastBurn](NotificationDeliverySuccessFastBurn.md). Typical slow-burn causes:
* a single tenant with a large list of invalid recipients (fix on the tenant side; consider lowering its rate limit);
* a provider that fails intermittently (watch `notifications_retried_total` vs `notifications_sent_total`);
* a channel whose circuit breaker flaps open and closed.

## Mitigation
Open a ticket, identify the dominant tenant/channel, and fix or contact the tenant. If caused by a provider, consider tuning `notification.retry.*` or switching providers.
