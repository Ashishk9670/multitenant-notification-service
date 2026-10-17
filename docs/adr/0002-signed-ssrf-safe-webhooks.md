# ADR 0002: Signed, SSRF-safe delivery-receipt webhooks

Status: accepted

## Context
Tenants want to learn when a delivery is SENT or DEAD without polling. The target URL is tenant-controlled, so
the service would otherwise become a server-side request forgery primitive against its own network.

## Decision
* One webhook per tenant (`/api/v1/webhook`). The signing secret is generated server side and shown only on
  creation and rotation.
* `WebhookUrlValidator` accepts https only, no credentials in the URL, and requires every resolved address to be
  public (loopback, private, link-local including cloud metadata, CGNAT, documentation, multicast and IPv6
  unique-local are rejected). It runs at registration and again before every delivery attempt.
* The HTTP client never follows redirects.
* Payloads are signed with HMAC-SHA256 over `{timestamp}.{body}`; headers `X-Webhook-Timestamp`,
  `X-Webhook-Signature: sha256=<hex>` and `X-Webhook-Id` let receivers verify and de-duplicate.
* Delivery is asynchronous with exponential backoff (`notification.webhook.*`) and never blocks or fails the
  notification delivery itself.

## Consequences
* Resolve-then-connect leaves a small DNS-rebinding window; the re-check before each attempt narrows it, and
  pinning the connection to the validated IP is a future hardening step.
* Receipts are best effort and in memory: pending retries are lost on restart. A durable outbox would fix this.
* The secret is stored in clear text because HMAC needs it; encrypt at rest if the threat model requires it.
