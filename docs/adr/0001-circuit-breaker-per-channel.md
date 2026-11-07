# ADR 0001: One circuit breaker per channel

Status: accepted

## Context
Email, SMS and push providers fail independently. A provider outage makes every delivery wait for a timeout and
then land in the retry topics, wasting consumer threads and hammering a provider that is already down.

## Decision
Each channel gets its own Resilience4j `CircuitBreaker` (`ChannelCircuitBreakers`), applied around the
`NotificationSender` call in `ChannelConsumers`. Settings live under `notification.circuit-breaker.*`
(failure-rate threshold, sliding window, minimum calls, open-state wait, half-open permits). State and call
metrics are exported by `resilience4j-micrometer` as `resilience4j_circuitbreaker_*` with `name` = channel.

An open breaker rejects the call immediately with `CallNotPermittedException`. The consumer treats it like any
other failure, so the event follows the normal retry/backoff/DLQ path and nothing is dropped.

## Which failures count
The breaker for a channel is shared by every tenant, so it must only reflect the health of the provider, never the
quality of one tenant's recipients. If a tenant sending to invalid addresses could open it, that tenant would make
healthy deliveries fail fast for everyone on the channel.

* **Count against the breaker:** transport errors, HTTP 5xx, 429 (throttling), 408 (timeout), 401/403 (rejected
  credentials, so the provider is unusable for everyone), and any unclassified exception (it may be the provider
  misbehaving, and opening is safer than hammering it).
* **Ignored (neither success nor failure):** `RecipientRejectedException` and other HTTP 4xx such as 400, 404, 410
  and 422 (invalid address or number, unregistered device token, malformed message). The probe permit is handed back
  with `releasePermission()` so a rejected half-open probe cannot starve the breaker.
* `ProviderException.isProviderFault()` encodes the HTTP rule; the SMTP adapter separates invalid-address failures
  (`SendFailedException` with invalid addresses, parse/preparation errors) from connection and authentication errors.
* The stub provider models both cases: a recipient containing `fail` is a rejected recipient, `flaky` is a transient
  provider failure.

An ignored rejection still follows the normal retry/DLQ path. Skipping retries for permanent rejections is a possible
follow-up and is deliberately not part of this change.

`CircuitBreakerIsolationIT` runs with the breaker enabled on real Kafka/Redis/Postgres and checks that a noisy
tenant's bad recipients do not stop another tenant's deliveries.

## Consequences
* A failing SMS provider no longer affects email or push.
* Rejected calls still consume a retry attempt; with long outages events can reach the DLQ. Tune
  `max-retries` and `wait-duration-in-open-state` together.
* Breakers are per instance (not shared through Redis), which is simple and good enough for provider health.
* Breakers are disabled in the other Testcontainers ITs because they intentionally fail deliveries on a shared channel;
  `CircuitBreakerIsolationIT` enables them to cover tenant isolation.
