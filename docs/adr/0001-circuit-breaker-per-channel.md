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

## Consequences
* A failing SMS provider no longer affects email or push.
* Rejected calls still consume a retry attempt; with long outages events can reach the DLQ. Tune
  `max-retries` and `wait-duration-in-open-state` together.
* Breakers are per instance (not shared through Redis), which is simple and good enough for provider health.
* Breakers are disabled in the Testcontainers ITs because they intentionally fail deliveries on a shared channel.
