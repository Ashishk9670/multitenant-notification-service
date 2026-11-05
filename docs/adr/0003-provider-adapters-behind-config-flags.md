# ADR 0003: Provider adapters behind configuration flags

Status: accepted

## Context
The service shipped with log-only stub senders. Real providers are needed, but CI, local development and demos must
keep working without credentials, and no real calls may happen by accident.

## Decision
`NotificationSender` stays the single abstraction. Per channel, `notification.provider.<channel>.type` selects the
implementation with `@ConditionalOnProperty`; the default is `stub`.

| Channel | Types |
|---|---|
| email | `stub`, `smtp` (Spring `JavaMailSender`), `ses` (SES v2 REST, SigV4) |
| sms | `stub`, `twilio` |
| push | `stub`, `fcm` (HTTP v1, bearer token from config) |

Adapters fail fast at startup when required settings are missing, never log credentials, and map non-2xx
responses to `ProviderException` (which reports whether the status is retryable). Base URLs are configurable so a
sandbox or emulator can be used. They are unit-tested against WireMock; no test talks to a real provider.

## Consequences
* Switching a channel to a real provider is a config change; the circuit breaker and retry flow apply unchanged.
* FCM needs an externally refreshed OAuth token; a token provider can replace the static value later.
* SES SigV4 is checked structurally against WireMock, not against AWS itself; validate it once in an SES sandbox.
